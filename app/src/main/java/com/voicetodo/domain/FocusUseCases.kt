package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FocusStartResult(
    val session: FocusSession,
    val requiresEndingCurrent: Boolean,
    val previousEndedAt: Instant?,
)

class FocusUseCases(
    private val repository: FocusRepository,
    private val scheduler: FocusScheduler,
    private val clock: Clock,
    private val todos: TodoRepository? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val mutations = Mutex()

    suspend fun startFocus(
        phase: FocusPhase = FocusPhase.Focus,
        endCurrent: Boolean = false,
    ): FocusStartResult = mutations.withLock {
        val settings = repository.settings()
        val current = repository.activeSession()
        if (current != null && !endCurrent) {
            return@withLock FocusStartResult(current, requiresEndingCurrent = true, previousEndedAt = null)
        }
        var previousEndedAt: Instant? = null
        if (current != null) {
            val ended = abandon(current)
            repository.save(ended)
            scheduler.cancel(ended.id)
            previousEndedAt = ended.endedAt
        }

        val now = clock.now()
        val duration = settings.durationOf(phase)
        val session = FocusSession(
            id = "focus-${now.toEpochMilli()}-${phase.ordinal}",
            phase = phase,
            startedAt = now,
            endsAt = now.plusSeconds(duration),
            lastResumedAt = now,
            durationSeconds = duration,
        )
        repository.save(session)
        scheduler.schedule(session.id, session.endsAt)
        FocusStartResult(session, requiresEndingCurrent = false, previousEndedAt = previousEndedAt)
    }

    suspend fun pause(): FocusStartResult? = mutations.withLock {
        val session = repository.activeSession() ?: return@withLock null
        if (session.state != FocusSessionState.Running) return@withLock FocusStartResult(session, false, null)
        val paused = session.copy(
            state = FocusSessionState.Paused,
            accumulatedFocusSeconds = session.accumulatedFocusSeconds + elapsedRunning(session),
        )
        repository.save(paused)
        scheduler.cancel(paused.id)
        FocusStartResult(paused, false, null)
    }

    suspend fun resume(): FocusStartResult? = mutations.withLock {
        val session = repository.activeSession() ?: return@withLock null
        if (session.state != FocusSessionState.Paused) return@withLock FocusStartResult(session, false, null)
        val now = clock.now()
        val remaining = (session.durationSeconds - session.accumulatedFocusSeconds).coerceAtLeast(0)
        val resumed = session.copy(
            state = FocusSessionState.Running,
            lastResumedAt = now,
            endsAt = now.plusSeconds(remaining),
        )
        repository.save(resumed)
        scheduler.schedule(resumed.id, resumed.endsAt)
        FocusStartResult(resumed, false, null)
    }

    suspend fun endEarly(): FocusStartResult? = mutations.withLock {
        val session = repository.activeSession() ?: return@withLock null
        val ended = abandon(session)
        repository.save(ended)
        scheduler.cancel(ended.id)
        FocusStartResult(ended, false, ended.endedAt)
    }

    suspend fun markEndedPendingConfirmation(): FocusStartResult? = mutations.withLock {
        val session = repository.activeSession() ?: return@withLock null
        if (session.state != FocusSessionState.Running || clock.now().isBefore(session.endsAt)) {
            return@withLock FocusStartResult(session, false, null)
        }
        val ended = session.copy(
            state = FocusSessionState.EndedPendingConfirmation,
            accumulatedFocusSeconds = session.durationSeconds,
            endedAt = session.endsAt,
        )
        repository.save(ended)
        FocusStartResult(ended, false, ended.endedAt)
    }

    suspend fun confirmEnded(): FocusStartResult? = mutations.withLock {
        val session = repository.activeSession() ?: return@withLock null
        if (session.state != FocusSessionState.EndedPendingConfirmation) {
            return@withLock FocusStartResult(session, false, null)
        }
        val confirmed = session.copy(
            state = FocusSessionState.Completed,
            completedPomodoros = if (session.phase == FocusPhase.Focus) 1 else 0,
        )
        repository.save(confirmed)
        FocusStartResult(confirmed, false, confirmed.endedAt)
    }

    suspend fun updateSettings(settings: FocusSettings) = mutations.withLock {
        repository.saveSettings(settings)
    }

    suspend fun snapshot(): FocusSnapshot {
        val settings = repository.settings()
        val sessions = repository.history()
        val active = sessions.lastOrNull { it.isActive() }
        val today = localDate(clock.now())
        val dates = List(7) { today.minusDays((6 - it).toLong()) }
        val focusSessions = sessions.filter {
            it.phase == FocusPhase.Focus && it.endedAt != null &&
                it.state != FocusSessionState.Running && it.state != FocusSessionState.Paused
        }
        return FocusSnapshot(
            session = active,
            settings = settings,
            todayCount = focusSessions.count {
                it.state == FocusSessionState.Completed && localDate(it.endedAt!!) == today
            },
            todaySeconds = focusSessions.filter { localDate(it.endedAt!!) == today }
                .sumOf { it.accumulatedFocusSeconds },
            lastSevenDaysCounts = dates.map { date ->
                date to focusSessions.count {
                    it.state == FocusSessionState.Completed && localDate(it.endedAt!!) == date
                }
            },
            lastSevenDaysSeconds = dates.map { date ->
                date to focusSessions.filter { localDate(it.endedAt!!) == date }
                    .sumOf { it.accumulatedFocusSeconds }
            },
        )
    }

    private fun localDate(instant: Instant): LocalDate =
        java.time.LocalDateTime.ofInstant(instant, zone).toLocalDate()

    suspend fun reconcileEnd() {
        val now = clock.now()
        val active = repository.activeSession() ?: return
        if (active.state == FocusSessionState.Running && !now.isBefore(active.endsAt)) markEndedPendingConfirmation()
    }

    private fun abandon(session: FocusSession): FocusSession {
        val accumulated = if (session.state == FocusSessionState.Running) {
            session.accumulatedFocusSeconds + elapsedRunning(session)
        } else session.accumulatedFocusSeconds
        return session.copy(
            state = FocusSessionState.Abandoned,
            accumulatedFocusSeconds = accumulated.coerceAtMost(session.durationSeconds),
            endedAt = clock.now(),
            completedPomodoros = 0,
        )
    }

    private fun elapsedRunning(session: FocusSession): Long {
        val elapsed = java.time.Duration.between(session.lastResumedAt, clock.now()).seconds
        val remaining = session.durationSeconds - session.accumulatedFocusSeconds
        return elapsed.coerceIn(0, remaining.coerceAtLeast(0))
    }

    private fun FocusSettings.durationOf(phase: FocusPhase): Long = when (phase) {
        FocusPhase.Focus -> focusMinutes * 60L
        FocusPhase.ShortBreak -> shortBreakMinutes * 60L
        FocusPhase.LongBreak -> longBreakMinutes * 60L
    }
}
