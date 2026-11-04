package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate

enum class FocusPhase { Focus, ShortBreak, LongBreak }

enum class FocusSessionState { Running, Paused, EndedPendingConfirmation, Abandoned, Completed }

data class FocusSettings(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val longBreakEvery: Int = 4,
) {
    init {
        require(focusMinutes > 0 && shortBreakMinutes > 0 && longBreakMinutes > 0 && longBreakEvery > 0) {
            "番茄时长必须大于零"
        }
    }
}

data class FocusSession(
    val id: String,
    val phase: FocusPhase,
    val startedAt: Instant,
    val endsAt: Instant,
    val lastResumedAt: Instant,
    val durationSeconds: Long,
    val pausedAt: Instant? = null,
    val accumulatedFocusSeconds: Long = 0,
    val completedPomodoros: Int = 0,
    val itemId: String? = null,
    val state: FocusSessionState = FocusSessionState.Running,
    val endedAt: Instant? = null,
) {
    fun isActive(): Boolean = state == FocusSessionState.Running ||
        state == FocusSessionState.Paused ||
        state == FocusSessionState.EndedPendingConfirmation
}

data class FocusSnapshot(
    val session: FocusSession?,
    val settings: FocusSettings,
    val todayCount: Int,
    val todaySeconds: Long,
    val lastSevenDaysCounts: List<Pair<LocalDate, Int>>,
    val lastSevenDaysSeconds: List<Pair<LocalDate, Long>>,
)
