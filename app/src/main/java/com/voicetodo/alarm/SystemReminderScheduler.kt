package com.voicetodo.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.voicetodo.domain.ReminderScheduler
import com.voicetodo.domain.TodoItem
import java.time.Instant
import java.time.ZoneId

/**
 * 能力状态：区分“能安排精确闹钟 / 能发通知 / 能录音”三件事，
 * 未具备时不谎称“已安排”。UI 与保存流程据此分别反馈。
 */
data class ReminderCapabilities(
    val canScheduleExactAlarm: Boolean,
    val canPostNotifications: Boolean,
    val canRecordAudio: Boolean,
) {
    /** 是否能真正完成“到点响铃”的调度。 */
    val canSchedule: Boolean get() = canScheduleExactAlarm
}

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

    fun capabilities(): ReminderCapabilities {
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return ReminderCapabilities(exact, notif, mic)
    }

    private fun alarmPendingIntent(
        itemId: String,
        title: String,
        atMillis: Long,
        revision: Long,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_AT_MILLIS, atMillis)
            putExtra(EXTRA_REVISION, revision)
            // 每个事项独立的 data，确保 PendingIntent 区分且可精确取消
            data = android.net.Uri.parse("voicetodo://alarm/$itemId")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCodeFor(itemId), intent, flags)
    }

    @SuppressLint("MissingPermission")
    override fun schedule(itemId: String, title: String, at: Instant, revision: Long): Boolean {
        // 精确闹钟权限/能力预检：不具备时不打系统调用，如实返回 false。
        if (!capabilities().canScheduleExactAlarm) return false

        val atMillis = at.toEpochMilli()
        val pi = alarmPendingIntent(itemId, title, atMillis, revision)
        // setAlarmClock 需要一个用户可见的 showIntent（点系统闹钟图标进入应用）
        val showIntent = Intent(context, com.voicetodo.MainActivity::class.java)
        val showPi = PendingIntent.getActivity(
            context,
            requestCodeFor(itemId) + SHOW_REQUEST_OFFSET,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showPi), pi)
            true
        } catch (e: SecurityException) {
            // 权限在预检后瞬间被撤回等竞态：不假报成功
            false
        }
    }

    override fun cancel(itemId: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = android.net.Uri.parse("voicetodo://alarm/$itemId")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, requestCodeFor(itemId), intent, flags)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /**
     * 对账：把仍存在且未来有效的提醒重新排入系统（冷启动/开机/权限恢复后调用）。
     * 过期提醒不补响——票边界内仅一条指定时刻事项，过期即不再安排。
     */
    fun reconcile(items: List<TodoItem>, now: Instant) {
        if (!capabilities().canScheduleExactAlarm) return
        for (item in items) {
            val remindAt = item.remindAt
            if (item.completedAt == null && remindAt != null && remindAt.isAfter(now)) {
                schedule(item.id, item.title, remindAt, item.reminderRevision)
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.voicetodo.action.ALARM_FIRE"
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AT_MILLIS = "extra_at_millis"
        const val EXTRA_REVISION = "extra_revision"

        // 用低 22 位保持在 PendingIntent requestCode 的可用范围，减少不同事项碰撞。
        private const val SHOW_REQUEST_OFFSET = 0x200000

        fun requestCodeFor(itemId: String): Int =
            (itemId.hashCode() and 0x7FFFFFF) and 0x1FFFFF
    }
}
