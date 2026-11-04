package com.voicetodo.domain

import java.time.Instant
import java.time.ZoneId

class MutableClock(
    var now: Instant,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : Clock {
    override fun now(): Instant = now
    fun zone(): ZoneId = zoneId
}
