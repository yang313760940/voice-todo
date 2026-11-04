package com.voicetodo.domain

import java.time.Instant

interface FocusScheduler {
    fun schedule(sessionId: String, at: Instant): Boolean
    fun cancel(sessionId: String)
}

class RecordedFocusScheduler(var scheduleSucceeds: Boolean = true) : FocusScheduler {
    val scheduled = mutableListOf<Pair<String, Instant>>()
    val cancelled = mutableListOf<String>()
    override fun schedule(sessionId: String, at: Instant): Boolean {
        scheduled += sessionId to at
        return scheduleSucceeds
    }
    override fun cancel(sessionId: String) { cancelled += sessionId }
}
