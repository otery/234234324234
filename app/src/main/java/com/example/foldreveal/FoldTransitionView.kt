package com.example.foldreveal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import android.view.View
import kotlin.math.abs

/**
 * 전환 렌더링 View — v5.
 *
 * v4 대비:
 *  - 셰이더가 진짜 원근 투영을 풀므로, 실제 힌지 위치/방향을 uniform 으로 넘깁니다.
 *  - 프레임 시간을 측정해 모션 블러 탭 수를 자동 조절합니다(적응형 품질).
 *    무거운 순간에는 스스로 가벼워져서 끊김 대신 약간의 디테일을 포기합니다.
 */
@SuppressLint("NewApi", "ViewConstructor")
class FoldTransitionView(context: Context) : View(context) {

    /** 모션 블러가 최대가 되는 기준 각속도 (deg/s) */
    var maxBlurVelocity = 260f

    /** 디스플레이 둥근 모서리 반경 (dp) */
    var cornerRadiusDp = 22f

    /**
     * 가상 카메라 거리. 작을수록 원근이 과장되고(광각), 클수록 평평해집니다(망원).
     * 1.6 ~ 3.0 사이가 자연스럽습니다.
     */
    var cameraDistance = 2.1f

    /** 실제 접힘선 정보 (없으면 중앙/세로로 폴백) */
    var hingePositionRatio = 0.5f
    var hingeIsVertical = true

    private var frame: Bitmap? = null
    private val spring = SpringAngle(stiffness = 420f, damping = 38f)

    private val shaderSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private var runtimeShader: RuntimeShader? = null

    // ---- 적응형 품질 ----
    /** 목표 프레임 예산 (ns). 120Hz 기준 8.3ms 이지만 여유를 둬 7ms 로 잡습니다. */
    private val frameBudgetNanos = 7_000_000L
    private var currentTaps = 8f
    private var lastDrawDurationNanos = 0L
    private var slowFrameStreak = 0
    private var fastFrameStreak = 0

    // 폴백 경로용
    private val camera = Camera()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint().apply { color = Color.BLACK }
    private val shaderMatrix = Matrix()

    private var frameRequester: (() -> Bitmap?)? = null
    private var running = false

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            frameRequester?.invoke()?.let { frame = it }
            spring.update(frameTimeNanos)
            adaptQuality()
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        // 셰이더 컴파일이 실패하면(기기/드라이버 차이) 예외 대신 폴백 경로를 쓰도록 보호합니다.
        if (shaderSupported) {
            runtimeShader = try {
                RuntimeShader(FoldShader.SRC)
            } catch (e: Throwable) {
                null
            }
        }
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setFrameSource(source: (() -> Bitmap?)?) {
        frameRequester = source
    }

    fun startRendering(initialFrame: Bitmap?, initialAngle: Float) {
        frame = initialFrame
        spring.snapTo(initialAngle)
        // 전환 시작마다 품질을 최대로 복구하고 다시 측정합니다.
        currentTaps = 8f
        slowFrameStreak = 0
        fastFrameStreak = 0
        if (!running) {
            running = true
            choreographer.postFrameCallback(frameCallback)
        }
        invalidate()
    }

    fun stopRendering() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
        frame = null
        invalidate()
    }

    fun setAngleDegrees(angle: Float) {
        spring.target = angle
    }

    fun isSettled(): Boolean =
        abs(spring.value - spring.target) < 0.1f && abs(spring.angularVelocity) < 1f

    /**
     * 직전 프레임의 실제 그리기 시간을 보고 블러 탭 수를 조절합니다.
     * 느리면 즉시 줄이고(끊김 방지 우선), 빠르면 천천히 늘립니다(진동 방지).
     */
    private fun adaptQuality() {
        if (lastDrawDurationNanos == 0L) return

        if (lastDrawDurationNanos > frameBudgetNanos) {
            slowFrameStreak++
            fastFrameStreak = 0
            if (slowFrameStreak >= 2 && currentTaps > 1f) {
                currentTaps = (currentTaps - 2f).coerceAtLeast(1f)
                slowFrameStreak = 0
            }
        } else if (lastDrawDurationNanos < frameBudgetNanos / 2) {
            fastFrameStreak++
            slowFrameStreak = 0
            if (fastFrameStreak >= 12 && currentTaps < 8f) {
                currentTaps = (currentTaps + 1f).coerceAtMost(8f)
                fastFrameStreak = 0
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = frame ?: return
        if (width == 0 || height == 0) return

        val start = System.nanoTime()
        if (shaderSupported && runtimeShader != null) {
            drawWithShader(canvas, bmp)
        } else {
            drawFallback(canvas, bmp)
        }
        lastDrawDurationNanos = System.nanoTime() - start
    }

    private fun drawWithShader(canvas: Canvas, bmp: Bitmap) {
        val shader = runtimeShader ?: return

        val contentShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            shaderMatrix.setScale(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            it.setLocalMatrix(shaderMatrix)
        }

        val progress = (spring.value / 180f).coerceIn(0f, 1f)
        val normalizedVelocity = (abs(spring.angularVelocity) / maxBlurVelocity).coerceIn(0f, 1f)
        val cornerRadiusPx = cornerRadiusDp * resources.displayMetrics.density

        shader.setInputShader("uContent", contentShader)
        shader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("uProgress", progress)
        shader.setFloatUniform("uVelocity", normalizedVelocity)
        shader.setFloatUniform("uCornerRadius", cornerRadiusPx)
        shader.setFloatUniform("uHingePos", hingePositionRatio)
        shader.setFloatUniform("uIsVertical", if (hingeIsVertical) 1f else 0f)
        shader.setFloatUniform("uBlurTaps", currentTaps)
        shader.setFloatUniform("uCameraDist", cameraDistance)

        paint.shader = shader
        paint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    // ---- Android 12 이하 폴백 ----

    private fun drawFallback(canvas: Canvas, bmp: Bitmap) {
        val w = width
        val h = height
        val t = (spring.value / 180f).coerceIn(0f, 1f)
        val smoothT = smoothstep(t)
        val rotation = lerp(80f, 0f, smoothT)
        val splitX = (w * hingePositionRatio).toInt().coerceIn(1, w - 1)

        drawHalf(canvas, bmp, Rect(0, 0, splitX, h), 0f, splitX, h, rotation, true)
        drawHalf(canvas, bmp, Rect(splitX, 0, w, h), splitX.toFloat(), w - splitX, h, -rotation, false)

        val shadowAlpha = (120 * (1f - smoothT)).toInt().coerceIn(0, 160)
        if (shadowAlpha > 0) {
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect((splitX - 6).toFloat(), 0f, (splitX + 6).toFloat(), h.toFloat(), shadowPaint)
        }
    }

    private fun drawHalf(
        canvas: Canvas,
        bitmap: Bitmap,
        srcRect: Rect,
        dstLeft: Float,
        halfWidth: Int,
        height: Int,
        rotationY: Float,
        pivotOnRight: Boolean,
    ) {
        canvas.save()
        camera.save()
        camera.setLocation(0f, 0f, -8f * resources.displayMetrics.density)
        camera.rotateY(rotationY)
        camera.getMatrix(matrix)
        camera.restore()

        val pivotX = if (pivotOnRight) halfWidth.toFloat() else 0f
        val pivotY = height / 2f
        matrix.preTranslate(-pivotX, -pivotY)
        matrix.postTranslate(pivotX + dstLeft, pivotY)

        canvas.concat(matrix)
        canvas.clipRect(dstLeft, 0f, dstLeft + halfWidth, height.toFloat())
        val dstRect = Rect(dstLeft.toInt(), 0, (dstLeft + halfWidth).toInt(), height)
        paint.alpha = 255
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        canvas.restore()
    }

    private fun smoothstep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
