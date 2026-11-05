package com.voicetodo.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voicetodo.domain.ReminderScheduler
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoReminder
import java.time.Instant
import java.time.ZoneId

/**
 * 真实系统闹钟调度：使用 [AlarmManager.setAlarmClock] + 系统持有的 [PendingIntent]。
 * 这不是应用内计时器——进程退出、锁屏后由系统在到点投递 [AlarmReceiver]。
 *
 * 调度前检查精确闹钟权限；无权限时**不抛异常、不打系统调用**，直接返回 false，
 * 由上层如实提示“提醒未就绪”，绝误报已安排。
 */
class SystemReminderScheduler(private val context: Context) : ReminderScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val registrations = context.getSharedPreferences("reminder_schedule_ids", Context.MODE_PRIVATE)

    fun capabilities(): ReminderCapabilities = readReminderCapabilities(context)

    private fun alarmPendingIntent(
        itemId: String,
        title: String,
        atMillis: Long,
        revision: Long,
        reminderId: String,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_AT_MILLIS, atMillis)
            putExtra(EXTRA_REVISION, revision)
            putExtra(EXTRA_REMINDER_ID, reminderId)
            data = alarmUri(itemId, reminderId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCodeFor(itemId, reminderId), intent, flags)
    }

    override fun schedule(itemId: String, title: String, at: Instant, revision: Long): Boolean =
        scheduleReminder(itemId, title, itemId, at, revision)

    @SuppressLint("MissingPermission")
    override fun scheduleReminder(itemId: String, title: String, reminderId: String, at: Instant,
        revision: Long): Boolean = synchronized(registrationLock) {
        // 精确闹钟权限/能力预检：不具备时不打系统调用，如实返回 false。
        if (!capabilities().canScheduleExactAlarm) return@synchronized false

        // Persist cancellation identities before the system call, including across process exit.
        val ids = registrations.getStringSet(itemId, emptySet()).orEmpty().toMutableSet().apply { add(reminderId) }
        if (!registrations.edit().putStringSet(itemId, ids).commit()) return@synchronized false

        val atMillis = at.toEpochMilli()
        val pi = alarmPendingIntent(itemId, title, atMillis, revision, reminderId)
        // setAlarmClock 需要一个用户可见的 showIntent（点系统闹钟图标进入应用）
        val showIntent = Intent(context, com.voicetodo.MainActivity::class.java).apply {
            data = alarmUri(itemId, reminderId).buildUpon().authority("show-alarm").build()
        }
        val showPi = PendingIntent.getActivity(
            context,
            requestCodeFor(itemId, reminderId) + SHOW_REQUEST_OFFSET,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showPi), pi)
            true
        } catch (e: SecurityException) {
            // 权限在预检后瞬间被撤回等竞态：不假报成功
            false
        }
    }

    override fun cancel(itemId: String) = synchronized(registrationLock) {
        val ids = registrations.getStringSet(itemId, emptySet()).orEmpty() + itemId
        ids.forEach { cancelOperation(itemId, it) }
        registrations.edit().remove(itemId).commit()
        Unit
    }

    override fun cancelReminder(itemId: String, reminderId: String) = synchronized(registrationLock) {
        cancelOperation(itemId, reminderId)
        val ids = registrations.getStringSet(itemId, emptySet()).orEmpty().toMutableSet().apply { remove(reminderId) }
        if (ids.isEmpty()) registrations.edit().remove(itemId).commit()
        else registrations.edit().putStringSet(itemId, ids).commit()
        Unit
    }

    private fun cancelOperation(itemId: String, reminderId: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = alarmUri(itemId, reminderId)
        }
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, requestCodeFor(itemId, reminderId), intent, flags) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /**
     * 对账：把仍存在且未来有效的提醒重新排入系统（冷启动/开机/权限恢复后调用）。
     * 过期提醒不补响；同一事项的多个提醒分别维护。
     */
    fun reconcile(items: List<TodoItem>, now: Instant) {
        if (!capabilities().canScheduleExactAlarm) return
        for (item in items) {
            if (item.completedAt != null) continue
            val reminders = item.reminders.ifEmpty {
                item.remindAt?.let { listOf(TodoReminder(item.id, it, revision = item.reminderRevision)) }.orEmpty()
            }
            for (reminder in reminders) if (reminder.at.isAfter(now) && reminder.deliveredAt == null) {
                scheduleReminder(item.id, item.title, reminder.id, reminder.at, reminder.revision)
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.voicetodo.action.ALARM_FIRE"
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AT_MILLIS = "extra_at_millis"
        const val EXTRA_REVISION = "extra_revision"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        /** 朗读不可用时的回退原因，供全屏提醒页展示。 */
        const val EXTRA_SPEECH_NOTICE = "extra_speech_notice"
        private val registrationLock = Any()

        private fun alarmUri(itemId: String, reminderId: String): Uri =
            Uri.Builder().scheme("voicetodo").authority("alarm").appendPath(itemId).apply {
                if (reminderId != itemId) appendPath(reminderId)
            }.build()

        // 用低 22 位保持在 PendingIntent requestCode 的可用范围，减少不同事项碰撞。
        private const val SHOW_REQUEST_OFFSET = 0x200000

        fun requestCodeFor(itemId: String): Int =
            (itemId.hashCode() and 0x7FFFFFF) and 0x1FFFFF

        fun requestCodeFor(itemId: String, reminderId: String): Int =
            if (itemId == reminderId) requestCodeFor(itemId) else requestCodeFor("$itemId\u0000$reminderId")
    }
}
