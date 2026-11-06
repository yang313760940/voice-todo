package com.voicetodo.domain

import java.time.Instant

/** 记录调度调用，用于断言“确实请求了系统闹钟 / 取消”。 */
class RecordedScheduler(
    /** 模拟系统调度是否成功（缺权限场景设为 false）。 */
    var scheduleSucceeds: Boolean = true,
) : ReminderScheduler {
    data class Scheduled(
        val itemId: String,
        val title: String,
        val at: Instant,
        val revision: Long,
        val reminderId: String = itemId,
    )
    val scheduled = mutableListOf<Scheduled>()
    val cancelled = mutableListOf<String>()
    val pending = linkedMapOf<Pair<String, String>, Scheduled>()
    val rejectedReminderIds = mutableSetOf<String>()

    override fun schedule(itemId: String, title: String, at: Instant, revision: Long): Boolean {
        val event = Scheduled(itemId, title, at, revision)
        scheduled += event
        if (scheduleSucceeds) pending[itemId to itemId] = event
        return scheduleSucceeds
    }
    override fun cancel(itemId: String) {
        cancelled += itemId
        pending.keys.removeAll { it.first == itemId }
    }
    override fun scheduleReminder(itemId: String, title: String, reminderId: String, at: Instant, revision: Long): Boolean {
        val event = Scheduled(itemId, title, at, revision, reminderId)
        scheduled += event
        val success = scheduleSucceeds && reminderId !in rejectedReminderIds
        if (success) pending[itemId to reminderId] = event
        return success
    }
    override fun cancelReminder(itemId: String, reminderId: String) {
        cancelled += itemId
        pending.remove(itemId to reminderId)
    }
}

/** 冻结时钟。 */
class FrozenClock(@Volatile var instant: Instant) : Clock {
    override fun now(): Instant = instant
}
