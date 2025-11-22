package com.trps02.p2pwalkietalkie

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

class DialogDetectorService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var statusBarBlockerView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("DialogDetector", "접근성 서비스 연결됨 (대기 상태)")
        // ▼▼▼ 삭제됨: 여기서 바로 차단막을 만들지 않습니다! ▼▼▼
        // addStatusBarBlocker()
    }

    // ▼▼▼ 추가됨: 외부 명령을 받아서 차단막을 제어합니다 ▼▼▼
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_BLOCK_STATUS_BAR") {
            addStatusBarBlocker()
        } else if (intent?.action == "ACTION_UNBLOCK_STATUS_BAR") {
            removeStatusBarBlocker()
        }
        return START_STICKY
    }
    // ▲▲▲ 추가됨 ▲▲▲

    private fun addStatusBarBlocker() {
        if (statusBarBlockerView != null) return // 이미 있으면 중복 생성 방지

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        statusBarBlockerView = FrameLayout(this).apply {
            setOnTouchListener { _, _ -> true } // 터치 흡수
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            getStatusBarHeight() + 50,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP

        try {
            windowManager?.addView(statusBarBlockerView, params)
            Log.d("DialogDetector", "상태바 차단막 활성화됨")
        } catch (e: Exception) {
            Log.e("DialogDetector", "차단막 생성 실패", e)
        }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return if (result > 0) result else 100
    }

    private fun removeStatusBarBlocker() {
        try {
            if (statusBarBlockerView != null && windowManager != null) {
                windowManager?.removeView(statusBarBlockerView)
                statusBarBlockerView = null
                Log.d("DialogDetector", "상태바 차단막 제거됨")
            }
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.text.isEmpty()) return

        val textList = event.text.toString()
        val keywords = listOf(
            "Wi-Fi Direct", "와이파이 다이렉트", "연결", "Connect",
            "초대", "Invitation", "수락", "Accept", "거절", "Decline", "허용", "Allow"
        )

        if (keywords.any { textList.contains(it, ignoreCase = true) }) {
            val intent = Intent(this, OverlayService::class.java)
            intent.action = "ACTION_MINIMIZE"
            startService(intent)
        }
    }

    override fun onInterrupt() {
        removeStatusBarBlocker()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeStatusBarBlocker()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val intent = Intent(this, OverlayService::class.java)
            if (action == KeyEvent.ACTION_DOWN) {
                intent.action = "ACTION_PTT_DOWN"
            } else if (action == KeyEvent.ACTION_UP) {
                intent.action = "ACTION_PTT_UP"
            }
            startService(intent)
            return true
        }
        return super.onKeyEvent(event)
    }
}