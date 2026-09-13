package com.example.foldreveal

import android.annotation.SuppressLint
import android.app.Notification
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * 모든 앱 위에서 동작하는 "힌지 펼침 오버레이" 서비스 (v3).
 *
 * v2 대비 달라진 점:
 *  - 전환 시작 시점의 스냅샷 한 장이 아니라, 전환 내내 매 프레임 최신 화면을 캡처해서 공급합니다.
 *    → 전환 도중에도 영상/애니메이션이 멈추지 않고 살아있게 보입니다.
 *  - 커버 디스플레이가 별도 Display 로 노출되면 CoverPresentation 을 띄워 같은 각도로 동기화합니다.
 *  - 캡처는 별도 HandlerThread 에서 처리해 메인 스레드 끊김을 줄였습니다.
 */
@SuppressLint("NewApi")
class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_CHANNEL_ID = "fold_overlay_channel"
        private const val NOTIFICATION_ID = 1

        /** 이 정도 각도 변화가 감지되면 전환이 시작됐다고 봅니다. */
        private const val START_DELTA_THRESHOLD = 3f
        /** 각도 변화가 이 시간(ms) 동안 없으면 전환이 끝난 것으로 봅니다. */
        private const val QUIET_TIMEOUT_MS = 260L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var hingeSensor: HingeAngleSensor

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var overlayView: FoldTransitionView? = null
    private var overlayAdded = false
    private var coverPresentation: CoverPresentation? = null

    private var previousAngle = 0f
    private var isTransitioning = false


    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("FoldCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private val quietRunnable = Runnable { endTransition() }

    /** 최근 캡처된 프레임. View 가 매 vsync 마다 이 값을 가져갑니다. */
    @Volatile
    private var latestFrame: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hingeSensor = HingeAngleSensor(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )

        if (resultCode != -1 && resultData != null && mediaProjection == null) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() = stopSelf()
                },
                mainHandler,
            )
            setupVirtualDisplay()
            setupOverlayView()
            setupCoverPresentationIfAvailable()
            startHingeListener()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hingeSensor.stop()
        mainHandler.removeCallbacks(quietRunnable)
        overlayView?.stopRendering()
        coverPresentation?.stopRendering()
        coverPresentation?.dismiss()
        removeOverlay()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread.quitSafely()
        latestFrame = null
        bufferA = null
        bufferB = null
    }

    // ---- 화면 캡처 ----

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 버퍼를 2장으로 줄이면 캡처 -> 표시 지연이 한 프레임 정도 짧아집니다.
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // 새 프레임이 도착할 때마다 비트맵으로 변환해 latestFrame 에 보관합니다.
        // (전환 중이 아닐 때는 변환 비용을 아끼려고 그냥 버립니다.)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                imageToBitmap(image)?.let { latestFrame = it }
            } catch (e: Exception) {
                // 캡처 실패(FLAG_SECURE 화면 등)는 조용히 무시합니다.
            } finally {
                image.close()
            }
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FoldOverlayCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler,
        )
    }

    /**
     * 매 프레임 새 Bitmap 을 만들면 GC 압력 때문에 전환이 끊깁니다.
     * 같은 크기의 버퍼 비트맵 두 장을 번갈아 재사용(더블 버퍼링)합니다.
     */
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var useBufferA = true

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bufferWidth = image.width + rowPadding / pixelStride

        // 렌더 중인 쪽이 아닌 버퍼에 씁니다.
        var target = if (useBufferA) bufferA else bufferB
        if (target == null || target.width != bufferWidth || target.height != image.height) {
            target = Bitmap.createBitmap(bufferWidth, image.height, Bitmap.Config.ARGB_8888)
            if (useBufferA) bufferA = target else bufferB = target
        }

        buffer.rewind()
        target.copyPixelsFromBuffer(buffer)
        useBufferA = !useBufferA
        return target
    }

    /** View 가 매 프레임 호출하는 프레임 공급자 */
    private val frameSource: () -> Bitmap? = { latestFrame }

    // ---- 오버레이 창 ----

    private fun setupOverlayView() {
        if (overlayAdded) return
        val view = FoldTransitionView(this)
        view.setFrameSource(frameSource)
        overlayView = view

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        view.visibility = View.GONE
        windowManager.addView(view, params)
        overlayAdded = true
    }

    private fun removeOverlay() {
        overlayView?.let {
            if (overlayAdded) {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    // 이미 제거됨
                }
            }
        }
        overlayAdded = false
        overlayView = null
    }

    /**
     * 커버 디스플레이가 별도 Display 로 노출되면 Presentation 을 띄웁니다.
     * 노출되지 않는 기기/상태에서는 조용히 건너뜁니다 (메인 오버레이만으로 동작).
     */
    private fun setupCoverPresentationIfAvailable() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplayId = Display.DEFAULT_DISPLAY

        val candidate = displayManager.displays
            .firstOrNull { it.displayId != defaultDisplayId && it.isValid && it.state == Display.STATE_ON }
            ?: return

        try {
            val presentation = CoverPresentation(this, candidate)
            presentation.show()
            presentation.setFrameSource(frameSource)
            coverPresentation = presentation
        } catch (e: Exception) {
            // 커버 디스플레이에 창을 띄울 수 없는 경우 무시
            coverPresentation = null
        }
    }

    // ---- 힌지 감지 & 전환 제어 ----

    private fun startHingeListener() {
        hingeSensor.start { angle ->
            val delta = abs(angle - previousAngle)
            previousAngle = angle

            if (!isTransitioning && delta > START_DELTA_THRESHOLD) {
                beginTransition(angle)
            }

            if (isTransitioning) {
                overlayView?.setAngleDegrees(angle)
                coverPresentation?.setAngleDegrees(angle)
                mainHandler.removeCallbacks(quietRunnable)
                mainHandler.postDelayed(quietRunnable, QUIET_TIMEOUT_MS)
            }
        }
    }

    private fun beginTransition(angle: Float) {
        isTransitioning = true
        val initial = latestFrame

        overlayView?.apply {
            setAngleDegrees(angle)
            visibility = View.VISIBLE
            startRendering(initial, angle)
        }
        coverPresentation?.apply {
            setAngleDegrees(angle)
            startRendering(initial, angle)
        }

        mainHandler.removeCallbacks(quietRunnable)
        mainHandler.postDelayed(quietRunnable, QUIET_TIMEOUT_MS)
    }

    /**
     * 각도 변화가 멈췄더라도 스프링이 아직 출렁이고 있으면 여운이 잘려 어색해집니다.
     * 스프링이 완전히 안착할 때까지 조금씩 더 기다렸다가 오버레이를 내립니다.
     */
    private fun endTransition() {
        val settled = overlayView?.isSettled() ?: true
        if (!settled) {
            mainHandler.postDelayed(quietRunnable, 48L)
            return
        }

        isTransitioning = false
        overlayView?.apply {
            stopRendering()
            visibility = View.GONE
        }
        coverPresentation?.stopRendering()
    }

    // ---- 알림 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "폴드 펼침 효과",
                NotificationManager.IMPORTANCE_MIN,
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("힌지 펼침 효과 실행 중")
            .setContentText("기기를 접거나 펼치면 전환 효과가 표시됩니다")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
}
