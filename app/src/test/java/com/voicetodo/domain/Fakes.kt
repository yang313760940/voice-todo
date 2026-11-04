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
    )
    val scheduled = mutableListOf<Scheduled>()
    val cancelled = mutableListOf<String>()

    override fun schedule(itemId: String, title: String, at: Instant, revision: Long): Boolean {
        scheduled += Scheduled(itemId, title, at, revision)
        return scheduleSucceeds
    }
    override fun cancel(itemId: String) {
        cancelled += itemId
    }
}

/** 冻结时钟。 */
class FrozenClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}
