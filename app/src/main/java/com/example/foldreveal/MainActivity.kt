package com.example.foldreveal

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 컨트롤 화면 (Compose 미사용 버전).
 *
 * 빌드 안정성을 위해 Compose / Material3 / coroutines 의존성을 모두 제거하고
 * 안드로이드 기본 View 로만 구성했습니다. 기능은 동일합니다.
 *
 *  1) "다른 앱 위에 표시" 권한 요청
 *  2) 화면 캡처(MediaProjection) 동의를 받고 OverlayService 시작
 *  3) 효과 중지
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PROJECTION = 1001
    }

    private lateinit var overlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "듀오 스타일 펼침 효과"
            setTextColor(Color.WHITE)
            textSize = 22f
        }
        root.addView(title)

        val desc = TextView(this).apply {
            text = "1) 오버레이 권한을 켜고, 2) 화면 캡처를 시작하면 어떤 앱을 쓰고 있어도 " +
                "기기를 접거나 펼칠 때 전환 효과가 나타납니다.\n\n" +
                "화면 캡처 중에는 시스템이 녹화 아이콘을 표시합니다. " +
                "이는 안드로이드 정책이라 끌 수 없습니다."
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 14f
            setPadding(0, dp(12), 0, dp(24))
        }
        root.addView(desc)

        overlayButton = Button(this).apply {
            text = "1. 오버레이 권한 요청"
            setOnClickListener { requestOverlayPermission() }
        }
        root.addView(overlayButton, buttonParams(dp(8)))

        val startButton = Button(this).apply {
            text = "2. 화면 캡처 권한 요청 + 효과 시작"
            setOnClickListener { requestScreenCaptureAndStart() }
        }
        root.addView(startButton, buttonParams(dp(8)))

        val stopButton = Button(this).apply {
            text = "효과 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }
        root.addView(stopButton, buttonParams(dp(8)))

        val hint = TextView(this).apply {
            text = "\n효과를 켠 뒤에는 이 앱을 닫아도 됩니다.\n" +
                "아무 앱이나 켜놓고 기기를 접었다 펼쳐보세요."
            setTextColor(Color.parseColor("#808080"))
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(hint)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        overlayButton.text = if (hasOverlayPermission()) {
            "1. 오버레이 권한 — 허용됨"
        } else {
            "1. 오버레이 권한 요청"
        }
    }

    private fun buttonParams(marginTop: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = marginTop }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (hasOverlayPermission()) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestScreenCaptureAndStart() {
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) return

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
    }
}
