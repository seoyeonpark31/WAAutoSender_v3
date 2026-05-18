package com.waauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import java.net.URLEncoder
import kotlin.coroutines.resume

class SenderService : Service() {

    companion object {
        private const val TAG = "SenderService"
        private const val CHANNEL_ID = "wa_sender_channel"
        private const val NOTIF_ID = 1001
        const val EXTRA_GROUP_ID = "group_id"
        var instance: SenderService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var messageSentContinuation: CancellableContinuation<Unit>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val groupId = intent?.getStringExtra(EXTRA_GROUP_ID)
        startForeground(NOTIF_ID, buildNotification("WhatsApp 전송 준비 중..."))
        scope.launch { startSending(groupId) }
        return START_NOT_STICKY
    }

    private suspend fun startSending(groupId: String?) {
        // 오버레이 권한 체크
        if (!Settings.canDrawOverlays(this)) {
            showToast("❌ '다른 앱 위에 표시' 권한이 없습니다!\n메인화면 분홍색 버튼을 눌러 허용해주세요")
            stopSelf(); return
        }

        // 그룹 로드
        val group = if (groupId != null) DataManager.getGroup(this, groupId) else null
        if (group == null) {
            showToast("❌ 그룹을 찾을 수 없습니다")
            stopSelf(); return
        }

        val contacts = group.contacts
        val message = group.message

        if (contacts.isEmpty()) {
            showToast("❌ [${group.name}] 연락처가 없습니다!\n편집에서 연락처를 추가해주세요")
            stopSelf(); return
        }
        if (message.isBlank()) {
            showToast("❌ [${group.name}] 메시지가 없습니다!\n편집에서 메시지를 입력해주세요")
            stopSelf(); return
        }
        if (WAAccessibilityService.instance == null) {
            showToast("❌ 접근성 서비스가 꺼져 있습니다!")
            stopSelf(); return
        }

        showToast("📤 [${group.name}] 전송 시작! ${contacts.size}명에게 보냅니다")

        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        var sentCount = 0

        for (contact in contacts) {
            updateNotification("[${group.name}] 전송 중... $sentCount/${contacts.size} (${contact.name})")
            Log.d(TAG, "전송 시도: ${contact.name} / ${contact.phone}")

            WAAccessibilityService.pendingMessage = message
            WAAccessibilityService.isReadyToSend = true

            val uri = Uri.parse("whatsapp://send?phone=${contact.phone}&text=${encodedMessage}")
            val openIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                startActivity(openIntent)

                withTimeout(15_000L) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        messageSentContinuation = cont
                    }
                }
                sentCount++
                Log.d(TAG, "전송 완료: ${contact.name} ($sentCount/${contacts.size})")

            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "타임아웃: ${contact.name}")
                showToast("⚠️ ${contact.name} 타임아웃 - 다음으로 넘어갑니다")
                WAAccessibilityService.isReadyToSend = false
            } catch (e: Exception) {
                Log.e(TAG, "전송 실패: ${e.message}")
                WAAccessibilityService.isReadyToSend = false
            }

            delay(DataManager.getDelayBetweenMessages())
        }

        updateNotification("전송 완료! $sentCount/${contacts.size}명")
        showToast("✅ [${group.name}] 완료! $sentCount/${contacts.size}명에게 보냈습니다")
        delay(3000L)
        stopSelf()
    }

    fun onMessageSent() {
        Log.d(TAG, "메시지 전송 확인됨 - 다음으로")
        messageSentContinuation?.resume(Unit)
        messageSentContinuation = null
    }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("📱 WA 자동 전송")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "WhatsApp 자동 전송", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
