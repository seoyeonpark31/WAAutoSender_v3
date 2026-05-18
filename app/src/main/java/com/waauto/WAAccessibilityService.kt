package com.waauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * WhatsApp UI 자동 조작 서비스
 * WhatsApp이 열리면 전송 버튼을 자동으로 탭합니다.
 */
class WAAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WAAutoService"
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        var pendingMessage: String? = null
        var isReadyToSend: Boolean = false
        var instance: WAAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastSendAttempt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "접근성 서비스 연결됨")
        showToast("WA 자동전송 서비스 활성화됨 ✅")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != WHATSAPP_PACKAGE) return
        if (!isReadyToSend) return

        // 중복 실행 방지 (1초 내 재시도 막기)
        val now = System.currentTimeMillis()
        if (now - lastSendAttempt < 1000) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            // 0.8초 후 전송 시도 (WhatsApp UI 로딩 대기)
            handler.postDelayed({ attemptSend() }, 800)
        }
    }

    private fun attemptSend() {
        if (!isReadyToSend) return
        lastSendAttempt = System.currentTimeMillis()

        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "root 없음")
            return
        }

        if (root.packageName?.toString() != WHATSAPP_PACKAGE) {
            Log.d(TAG, "WhatsApp이 포그라운드가 아님: ${root.packageName}")
            return
        }

        Log.d(TAG, "WhatsApp 감지, 전송 버튼 탐색 중...")

        // 전송 버튼 찾기 (여러 전략)
        val sendNode = findSendButton(root)

        if (sendNode != null) {
            Log.d(TAG, "전송 버튼 발견: ${sendNode.viewIdResourceName}")
            isReadyToSend = false
            sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({
                SenderService.instance?.onMessageSent()
            }, 300)
        } else {
            Log.d(TAG, "전송 버튼 못 찾음, 좌표 탭 시도")
            // 버튼을 못 찾으면 화면 우하단 좌표 탭으로 대체
            tapSendByCoordinate()
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 전략 1: 알려진 View ID로 찾기
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_btn",
            "com.whatsapp:id/mic_or_send",
            "com.whatsapp:id/audio_or_send"
        )
        for (id in sendIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val node = nodes.firstOrNull { it.isClickable }
            if (node != null) return node
        }

        // 전략 2: 콘텐츠 설명(보내기/Send)으로 찾기
        val descriptions = listOf("보내기", "Send", "전송")
        for (desc in descriptions) {
            val nodes = root.findAccessibilityNodeInfosByText(desc)
            val node = nodes.firstOrNull { it.isClickable }
            if (node != null) return node
        }

        // 전략 3: 화면 우하단의 클릭 가능한 버튼 탐색
        return findBottomRightButton(root)
    }

    private fun findBottomRightButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val display = resources.displayMetrics
        val screenWidth = display.widthPixels
        val screenHeight = display.heightPixels

        // 화면 우하단 30% 영역에서 클릭 가능한 버튼 찾기
        val rightThreshold = (screenWidth * 0.6).toInt()
        val bottomThreshold = (screenHeight * 0.6).toInt()

        return findNodeInRegion(node, rightThreshold, bottomThreshold, screenWidth, screenHeight)
    }

    private fun findNodeInRegion(
        node: AccessibilityNodeInfo,
        left: Int, top: Int, right: Int, bottom: Int
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (node.isClickable && node.childCount == 0) {
            if (bounds.left >= left && bounds.top >= top &&
                bounds.right <= right && bounds.bottom <= bottom) {
                val className = node.className?.toString() ?: ""
                if (className.contains("Button") || className.contains("Image")) {
                    return node
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeInRegion(child, left, top, right, bottom)
            if (result != null) return result
        }
        return null
    }

    private fun tapSendByCoordinate() {
        val display = resources.displayMetrics
        val screenWidth = display.widthPixels
        val screenHeight = display.heightPixels

        // 전송 버튼은 보통 화면 우하단에 위치
        val x = (screenWidth * 0.92f)
        val y = (screenHeight * 0.88f)

        Log.d(TAG, "좌표 탭: ($x, $y)")

        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "좌표 탭 완료")
                isReadyToSend = false
                handler.postDelayed({
                    SenderService.instance?.onMessageSent()
                }, 500)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.d(TAG, "좌표 탭 취소됨")
            }
        }, handler)
    }

    private fun showToast(msg: String) {
        handler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}
}
