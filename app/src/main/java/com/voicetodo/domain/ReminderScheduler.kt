package com.voicetodo.domain

import java.time.Instant

/**
 * 提醒调度接缝：真实实现调用系统 AlarmManager（setAlarmClock + PendingIntent），
 * 测试用内存记录实现，验证“保存后确实请求了系统调度”这一行为，而不是应用内计时器。
 *
 * [schedule] 返回是否**真正排入系统**：权限缺失/系统拒绝时返回 false，绝不假报成功。
 */
interface ReminderScheduler {
    /** 为某事项在某时刻安排一次性系统闹钟；成功排入返回 true。 */
    fun schedule(itemId: String, title: String, at: Instant, revision: Long): Boolean

    /** 独立安排一个提醒；旧适配器只能支持原事项身份，不能假报多提醒成功。 */
    fun scheduleReminder(itemId: String, title: String, reminderId: String, at: Instant, revision: Long): Boolean =
        reminderId == itemId && schedule(itemId, title, at, revision)

    /** 取消某事项的全部未来闹钟。 */
    fun cancel(itemId: String)

    fun cancelReminder(itemId: String, reminderId: String) {
        if (reminderId == itemId) cancel(itemId)
    }
}
