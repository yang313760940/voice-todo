package com.voicetodo.alarm

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.voicetodo.MainActivity
import com.voicetodo.VoiceTodoApp

/**
 * 持续响铃前台服务：循环播放本地提示音；默认 60 秒后停止发声并保留通知。
 * 必须由系统闹钟触发（用户设定的闹钟场景属 FGS 后台启动例外）。
 */
class RingingService : Service() {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemId = intent?.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: "unknown"
        val title = intent?.getStringExtra(SystemReminderScheduler.EXTRA_TITLE) ?: "提醒"

        val notification = buildNotification(itemId, title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(AlarmReceiver.notificationIdFor(itemId), notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(AlarmReceiver.notificationIdFor(itemId), notification)
        }

        startRinging()
        // 默认最多 60 秒发声，随后停止声音并保留通知
        stopRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { stopSoundOnly() }
        stopRunnable = r
        handler.postDelayed(r, DEFAULT_RING_MILLIS)
        return START_NOT_STICKY
    }

    private fun startRinging() {
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
            player = null
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

    private fun buildNotification(itemId: String, title: String): Notification {
        val openPi = android.app.PendingIntent.getActivity(
            this, itemId.hashCode(), Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, VoiceTodoApp.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("语音提醒")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(openPi)
            .build()
    }

    override fun onDestroy() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_RING_MILLIS = 60_000L
    }
}
