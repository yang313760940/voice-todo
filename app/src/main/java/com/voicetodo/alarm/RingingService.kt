package com.voicetodo.alarm

import android.app.Service
import android.app.NotificationManager
import android.app.KeyguardManager
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
import com.voicetodo.domain.TodoItem
import com.voicetodo.speech.AndroidReminderSpeaker
import com.voicetodo.speech.ReminderSpeaker
import com.voicetodo.speech.SpeechAvailability
import com.voicetodo.speech.SpeechDecision
import com.voicetodo.speech.SpeechFallbackReason
import com.voicetodo.speech.decideReminderSpeech
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
    private var speaker: ReminderSpeaker? = null
    private var speakingKey: Pair<String, String>? = null
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        liveService = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = ReminderToken.from(intent)
        if (token == null) { stopSelf(startId); return START_NOT_STICKY }
        scope.launch {
            var publishedByService = false
            try {
                val repo = (application as VoiceTodoApp).repository
                val deviceLocked = isDeviceLocked()
                // 先按当前事项准备语音引擎；真正的投递仍由仓库事务再次校验。
                val candidate = repo.findById(token.itemId)
                val speechEligible = candidate != null && candidate.readAloud &&
                    (!deviceLocked || candidate.readAloudWhenLocked)
                val activeSpeaker = if (speechEligible && !destroyed) {
                    speakerFactory(this@RingingService).also {
                        speaker?.release()
                        speaker = it
                    }
                } else {
                    null
                }
                val availability = activeSpeaker?.prepare() ?: SpeechAvailability.Ready
                val delivered = repo.deliverReminder(
                    token.itemId, token.reminderId, token.revision, token.atMillis, Instant::now,
                ) { item ->
                    withContext(Dispatchers.Main.immediate) {
                        val decision = effectiveDecision(
                            decideReminderSpeech(
                                item.readAloud, item.readAloudWhenLocked, deviceLocked, availability,
                            ),
                            activeSpeaker,
                        )
                        val notification = AlarmNotification.build(
                            this@RingingService, item.id, item.title, token.reminderId, token.revision,
                            speechNotice = (decision as? SpeechDecision.Fallback)?.reason,
                        )
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
                            when (decision) {
                                SpeechDecision.Speak -> {
                                    val key = token.itemId to token.reminderId
                                    speakingKey = key
                                    val started = activeSpeaker?.speak(item.title) {
                                        scope.launch {
                                            if (speakingKey == key) {
                                                speakingKey = null
                                                if (!destroyed && activeReminderKeys.isNotEmpty() &&
                                                    item.persistentRing) startRinging(true)
                                            }
                                        }
                                    } == true
                                    if (!started) {
                                        speakingKey = null
                                        showSpeechFallback(item, token, SpeechFallbackReason.START_FAILED)
                                        startRinging(item.persistentRing)
                                    }
                                }
                                is SpeechDecision.Fallback -> startRinging(item.persistentRing)
                                SpeechDecision.Silent -> startRinging(item.persistentRing)
                            }
                            if (item.persistentRing) {
                                stopRunnable?.let { handler.removeCallbacks(it) }
                                val r = Runnable { stopSoundOnly() }
                                stopRunnable = r
                                handler.postDelayed(r, DEFAULT_RING_MILLIS)
                            }
                            true
                        } catch (e: Exception) {
                            android.util.Log.w("RingingService", "startForeground failed", e)
                            AlarmReceiver.showFallbackNotification(this@RingingService, item, token.reminderId)
                        }
                    }
                }
                if (delivered) {
                    (application as VoiceTodoApp).directBootStore.removeReminder(
                        token.itemId,
                        token.reminderId,
                    )
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

    /** 语音引擎未能预先准备时（例如并发修改），本次退回既有提示音而非误报资源缺失。 */
    private fun effectiveDecision(
        decision: SpeechDecision,
        activeSpeaker: ReminderSpeaker?,
    ): SpeechDecision =
        if (decision == SpeechDecision.Speak && activeSpeaker == null) SpeechDecision.Silent
        else decision

    private fun showSpeechFallback(item: TodoItem, token: ReminderToken, reason: String) {
        val notificationId = AlarmReceiver.notificationIdFor(item.id, token.reminderId)
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                notificationId,
                AlarmNotification.build(
                    this, item.id, item.title, token.reminderId, token.revision, speechNotice = reason,
                ),
            )
        }.onFailure { android.util.Log.w("RingingService", "展示语音回退原因失败", it) }
    }

    /**
     * 持续响铃时循环播放并在 [DEFAULT_RING_MILLIS] 后停止；普通提醒只播放一次默认提示音，
     * 由系统通知负责后续提示，不长期占用音频。
     */
    private fun startRinging(persistent: Boolean) {
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
                isLooping = persistent
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
        stopSound()
        // 停止前台态，通知保留（AlarmReceiver 已单独发通知）
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun stopSound() {
        speakingKey = null
        speaker?.stop()
        try {
            player?.stop()
        } catch (_: Exception) {
        } finally {
            player?.release()
            player = null
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (liveService === this) liveService = null
        scope.cancel()
        stopRunnable?.let { handler.removeCallbacks(it) }
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        speaker?.release()
        speaker = null
        speakingKey = null
        activeReminderKeys.clear()
        foregroundNotificationId = null
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_RING_MILLIS = 60_000L
        private var liveService: RingingService? = null

        /** 测试可替换为假实现，避免依赖设备语音引擎。 */
        internal var speakerFactory: (android.content.Context) -> ReminderSpeaker =
            { AndroidReminderSpeaker(it) }

        /**
         * 只停止被关闭/延后的那一条提醒的朗读；同刻其它提醒仍在响时保留它们的声音。
         * 进程内服务实例由 [onCreate]/[onDestroy] 维护，不需要额外启动服务。
         */
        internal fun stopReminderSpeech(itemId: String, reminderId: String) {
            val service = liveService ?: return
            if (service.speakingKey != itemId to reminderId) return
            service.speakingKey = null
            service.speaker?.stop()
        }

        /**
         * 关闭/完成动作以服务持有的活跃提醒集合为准。
         *
         * `NotificationManager.activeNotifications` 的移除是异步的；若在锁屏或厂商系统
         * 延迟刷新时立即据此判断，会把已关闭的最后一条提醒误认为仍然存在，导致前台服务
         * 和声音继续运行。返回 false 表示当前进程没有存活的响铃服务，由调用方走无服务兜底。
         */
        internal fun stopReminder(
            itemId: String,
            reminderId: String,
        ): Boolean {
            val service = liveService ?: return false
            val key = itemId to reminderId
            service.activeReminderKeys.remove(key)
            if (service.speakingKey == key) {
                service.speakingKey = null
                service.speaker?.stop()
            }
            service.getSystemService(NotificationManager::class.java)
                .cancel(AlarmReceiver.notificationIdFor(itemId, reminderId))
            if (service.activeReminderKeys.isEmpty()) {
                service.stopSound()
                service.stopForeground(STOP_FOREGROUND_REMOVE)
                service.stopSelf()
            }
            return true
        }
    }

    /** 无法确认锁屏状态时按锁屏处理，优先避免读出正文。 */
    private fun isDeviceLocked(): Boolean = try {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
    } catch (e: Exception) {
        true
    }
}
