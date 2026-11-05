package com.voicetodo.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voicetodo.VoiceTodoApp
import com.voicetodo.domain.ReminderActionOutcome
import com.voicetodo.domain.SnoozeOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 处理通知/响铃界面的三种动作：完成、延后、关闭本次提醒。
 *
 * 关闭不完成事项；完成取消本实例未来提醒；延后只重新登记一条系统闹钟，
 * 不改变截止时间、提醒列表或下一重复周期。所有动作经 [TodoUseCases] 串行执行，
 * 重复点击（同一实例已处理）不会重复调度。
 */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val itemId = intent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: return
        val reminderId = intent.getStringExtra(SystemReminderScheduler.EXTRA_REMINDER_ID) ?: itemId
        stopOngoingReminder(context, itemId, reminderId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context, action, itemId, intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "处理提醒动作失败 action=$action item=$itemId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 独立的处理入口，供测试直接驱动而不依赖广播投递。 */
    suspend fun handle(context: Context, action: String, itemId: String, intent: Intent): ReminderActionOutcome? {
        val useCases = (context.applicationContext as VoiceTodoApp).useCases
        val reminderId = intent.getStringExtra(SystemReminderScheduler.EXTRA_REMINDER_ID) ?: itemId
        val revision = intent.getLongExtra(SystemReminderScheduler.EXTRA_REVISION, Long.MIN_VALUE)
        return when (action) {
            ACTION_COMPLETE -> useCases.completeFromReminder(itemId, reminderId, revision)
            ACTION_DISMISS, ACTION_STOP -> useCases.dismissReminder(itemId, reminderId, revision)
            ACTION_SNOOZE -> {
                val customAt = intent.getStringExtra(EXTRA_SNOOZE_AT)?.let(Instant::parse)
                if (customAt != null) useCases.snoozeFromReminderAt(itemId, customAt, reminderId, revision)
                else {
                    val minutes = intent.getLongExtra(EXTRA_SNOOZE_MINUTES, SNOOZE_DEFAULT_MINUTES)
                    useCases.snoozeFromReminder(itemId, SnoozeOption.fromMinutes(minutes), reminderId, revision)
                }
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "AlarmActionReceiver"
        const val ACTION_COMPLETE = "com.voicetodo.action.ALARM_COMPLETE"
        const val ACTION_SNOOZE = "com.voicetodo.action.ALARM_SNOOZE"
        const val ACTION_DISMISS = "com.voicetodo.action.ALARM_DISMISS"
        /** 兼容旧入口：关闭提醒等同“关闭本次提醒”。 */
        const val ACTION_STOP = "com.voicetodo.action.ALARM_STOP"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
        const val EXTRA_SNOOZE_AT = "extra_snooze_at"
        const val SNOOZE_DEFAULT_MINUTES = 10L

        private val SUPPORTED_ACTIONS = setOf(ACTION_COMPLETE, ACTION_SNOOZE, ACTION_DISMISS, ACTION_STOP)

        /**
         * 停止该提醒的声音并清除它的通知；若同刻还有其它提醒在响，保留它们。
         * 关闭动作把旧通知移除后，若已无剩余提醒则停止前台服务。
         */
        fun stopOngoingReminder(context: Context, itemId: String, reminderId: String) {
            RingingService.stopReminderSpeech(itemId, reminderId)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(AlarmReceiver.notificationIdFor(itemId, reminderId))
            // 仅当同频道已无其它提醒通知时才停止前台服务，避免误停其它仍在响的提醒。
            val remaining = nm.activeNotifications.count {
                it.notification.channelId == VoiceTodoApp.CHANNEL_REMINDER
            }
            if (remaining == 0) {
                context.stopService(Intent(context, RingingService::class.java))
            }
        }
    }
}
