package com.voicetodo.domain

import java.time.LocalDateTime

/** 记录调度调用，用于断言“确实请求了系统闹钟 / 取消”。 */
class RecordedScheduler(
    /** 模拟系统调度是否成功（缺权限场景设为 false）。 */
    var scheduleSucceeds: Boolean = true,
) : ReminderScheduler {
    data class Scheduled(val itemId: String, val title: String, val at: LocalDateTime)
    val scheduled = mutableListOf<Scheduled>()
    val cancelled = mutableListOf<String>()

    override fun schedule(itemId: String, title: String, at: LocalDateTime): Boolean {
        scheduled += Scheduled(itemId, title, at)
        return scheduleSucceeds
    }
    override fun cancel(itemId: String) {
        cancelled += itemId
    }
}

/** 冻结时钟。 */
class FrozenClock(var instant: LocalDateTime) : Clock {
    override fun now(): LocalDateTime = instant
}
