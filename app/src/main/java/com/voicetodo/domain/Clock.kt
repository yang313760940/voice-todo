package com.voicetodo.domain

import java.time.Instant

/** 可注入的时钟接缝：生产用系统时钟，测试冻结时间，避免“明天”随执行日漂移。 */
interface Clock {
    fun now(): Instant
}

/** 系统时钟实现。 */
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
