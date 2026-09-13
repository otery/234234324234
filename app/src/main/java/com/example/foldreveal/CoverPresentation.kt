package com.example.foldreveal

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager

/**
 * 커버(외부) 디스플레이에 동일한 펼침 효과를 띄우는 Presentation.
 *
 * 폴드8은 접혀 있을 때 커버 화면이, 펼쳤을 때 메인 화면이 활성화됩니다.
 * 두 화면에 같은 힌지 각도를 공급해 애니메이션을 동기화하면, 실제로 기기를 펼칠 때
 * "커버에서 보던 화면이 안쪽으로 이어지는" 듀오 같은 연출에 한 걸음 더 가까워집니다.
 *
 * 주의: 삼성 기기에서 커버 디스플레이가 DisplayManager 에 항상 별도 Display 로 노출되는 것은
 * 아닙니다. 노출되지 않는 기기/상태에서는 [FoldDisplayManager] 가 이 Presentation 을
 * 생성하지 않고 메인 오버레이만 사용합니다.
 */
@SuppressLint("NewApi")
class CoverPresentation(
    context: Context,
    display: Display,
) : Presentation(context, display) {

    private var transitionView: FoldTransitionView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )

        val view = FoldTransitionView(context)
        transitionView = view
        setContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun setFrameSource(source: (() -> Bitmap?)?) {
        transitionView?.setFrameSource(source)
    }

    fun startRendering(initialFrame: Bitmap?, initialAngle: Float) {
        transitionView?.startRendering(initialFrame, initialAngle)
    }

    fun stopRendering() {
        transitionView?.stopRendering()
    }

    fun setAngleDegrees(angle: Float) {
        transitionView?.setAngleDegrees(angle)
    }

    /** 기기가 보고하는 실제 접힘선 정보를 렌더러에 반영합니다. */
    fun setHingeGeometry(positionRatio: Float, isVertical: Boolean) {
        transitionView?.hingePositionRatio = positionRatio
        transitionView?.hingeIsVertical = isVertical
    }
}
