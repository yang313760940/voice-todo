package com.voicetodo.alarm

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voicetodo.VoiceTodoApp
import kotlinx.coroutines.*
import java.time.Instant

/**
 * 持续响铃前台服务：循环播放本地提示音；默认 60 秒后停止发声并保留通知。
 * 必须由系统闹钟触发（用户设定的闹钟场景属 FGS 后台启动例外）。
 */
class RingingService : Service() {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundNotificationId: Int? = null
    private val activeReminderKeys = mutableSetOf<Pair<String, String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = ReminderToken.from(intent)
        if (token == null) { stopSelf(startId); return START_NOT_STICKY }
        scope.launch {
            var publishedByService = false
            try {
                val repo = (application as VoiceTodoApp).repository
                val delivered = repo.deliverReminder(
                    token.itemId, token.reminderId, token.revision, token.atMillis, Instant::now,
                ) { item ->
                    withContext(Dispatchers.Main.immediate) {
                        val notification = AlarmNotification.build(
                            this@RingingService, item.id, item.title, token.reminderId)
                        val notificationId = AlarmReceiver.notificationIdFor(item.id, token.reminderId)
                        try {
                            if (foregroundNotificationId == null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    startForeground(notificationId, notification,
                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                                } else {
                                    startForeground(notificationId, notification)
                                }
                                foregroundNotificationId = notificationId
                            } else {
                                getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                            }
                            publishedByService = true
                            activeReminderKeys += token.itemId to token.reminderId
                            startRinging()
                            stopRunnable?.let { handler.removeCallbacks(it) }
                            val r = Runnable { stopSoundOnly() }
                            stopRunnable = r
                            handler.postDelayed(r, DEFAULT_RING_MILLIS)
                            true
                        } catch (e: Exception) {
                            android.util.Log.w("RingingService", "startForeground failed", e)
                            AlarmReceiver.showFallbackNotification(this@RingingService, item, token.reminderId)
                        }
                    }
                }
                if ((!delivered || !publishedByService) && activeReminderKeys.isEmpty()) {
                    stopSelfResult(startId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RingingService", "Validation/delivery failed", e)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRinging() {
        player?.release()
        player = null
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@RingingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            player?.release()
            player = null
            android.util.Log.w("RingingService", "Audio playback failed", e)
        }
    }

    /** 到 60 秒：停止声音，但保留通知（由 AlarmReceiver 的通知继续展示）。 */
    private fun stopSoundOnly() {
        try {
            player?.stop()
        } catch (_: Exception) {
        } finally {
            player?.release()
            player = null
        }
        // 停止前台态，通知保留（AlarmReceiver 已单独发通知）
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onDestroy() {
        scope.cancel()
        stopRunnable?.let { handler.removeCallbacks(it) }
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        activeReminderKeys.clear()
        foregroundNotificationId = null
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_RING_MILLIS = 60_000L
    }
}
