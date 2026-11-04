package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FocusUseCasesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = Instant.parse("2026-11-05T01:00:00Z")
    private val clock = MutableClock(start, zone)
    private val repo = InMemoryFocusRepository()
    private val scheduler = RecordedFocusScheduler()
    private lateinit var useCases: FocusUseCases

    @Test fun singleActiveSessionPausesWithoutCountingAndEndsEarlyWithAccumulatedTime() = runTest {
        useCases = FocusUseCases(repo, scheduler, clock)
        val first = useCases.startFocus().session
        assertEquals(FocusSessionState.Running, first.state)

        clock.now = start.plusSeconds(600)
        val paused = useCases.pause()!!.session
        assertEquals(FocusSessionState.Paused, paused.state)

        clock.now = start.plusSeconds(1200)
        val resumed = useCases.resume()!!.session
        assertEquals(FocusSessionState.Running, resumed.state)
        assertEquals(start.plusSeconds(2100), resumed.endsAt)

        clock.now = start.plusSeconds(2100)
        val ended = useCases.endEarly()!!.session
        assertEquals(FocusSessionState.Abandoned, ended.state)
        assertEquals(1500, ended.accumulatedFocusSeconds)
        assertEquals(0, useCases.snapshot().todayCount)
    }

    @Test fun completeFocusAddsExactlyOneCountAndDoesNotCompleteTodo() = runTest {
        val todos = InMemoryTodoRepository()
        val item = TodoItem(id = "todo", title = "Work", createdAt = start)
        todos.save(item)
        useCases = FocusUseCases(repo, scheduler, clock, todos)
        useCases.startFocus(itemId = item.id)
        clock.now = start.plusSeconds(1500)
        useCases.markEndedPendingConfirmation()
        val result = useCases.confirmEnded()!!
        assertEquals(FocusSessionState.Completed, result.session.state)
        assertEquals(1, useCases.snapshot().todayCount)
        assertEquals(1500, useCases.snapshot().todaySeconds)
        assertNull(todos.findById(item.id)!!.completedAt)
    }

    @Test fun startingAnotherFocusEndsCurrentAndWarnsBeforeBindingTodo() = runTest {
        val todos = InMemoryTodoRepository()
        val item = TodoItem(id = "todo", title = "Work", createdAt = start)
        todos.save(item)
        useCases = FocusUseCases(repo, scheduler, clock, todos)
        useCases.startFocus()
        val blocked = useCases.startFocus(itemId = item.id, endCurrent = false)
        assertTrue(blocked.requiresEndingCurrent)
        assertEquals(start, blocked.session.startedAt)

        val result = useCases.startFocus(itemId = item.id, endCurrent = true)
        assertFalse(result.requiresEndingCurrent)
        assertEquals(start, result.previousEndedAt)
        assertEquals(item.id, result.session.itemId)
    }
}
