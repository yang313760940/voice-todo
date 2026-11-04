package com.voicetodo.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TodoEditTest {
    private val now = Instant.parse("2026-11-04T09:00:00Z")
    private val repo = InMemoryTodoRepository()
    private val scheduler = RecordedScheduler()
    private val clock = FrozenClock(now)
    private val uc = TodoUseCases(repo, scheduler, clock)

    @Test fun changingReminderPersistsThenCancelsAndSchedulesNewRevision() = runTest {
        val item = uc.confirmDraft("Original", now.plusSeconds(60)).item
        val result = uc.updateItem(item.id, TodoEdit(" Edited ", LocalDate.of(2026, 11, 5),
            now.minusSeconds(1), now.plusSeconds(120)))
        assertEquals("Edited", result.item.title)
        assertEquals(1L, result.item.reminderRevision)
        assertEquals(result.item, repo.findById(item.id))
        assertTrue(result.reminderChanged)
        assertEquals(ReminderOutcome.Scheduled, result.outcome)
        assertEquals(listOf(item.id), scheduler.cancelled)
        assertEquals(1L, scheduler.scheduled.last().revision)
        assertEquals(now.plusSeconds(120), scheduler.scheduled.last().at)
    }

    @Test fun clearingReminderCancelsWithoutScheduling() = runTest {
        val item = uc.confirmDraft("Title", now.plusSeconds(60)).item
        val result = uc.updateItem(item.id, TodoEdit("Title", null, null, null))
        assertEquals(1L, result.item.reminderRevision)
        assertNull(result.item.remindAt)
        assertTrue(result.reminderChanged)
        assertEquals(ReminderOutcome.NotRequested, result.outcome)
        assertEquals(listOf(item.id), scheduler.cancelled)
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test fun titlePlanAndDeadlineDoNotChangeSchedulingEvenAfterReminderTimePasses() = runTest {
        val at = now.plusSeconds(60)
        val item = uc.confirmDraft("Before", at).item
        clock.instant = at.plusSeconds(1)
        val result = uc.updateItem(item.id, TodoEdit(" After ", LocalDate.of(2026, 11, 5), now, at))
        assertEquals(0L, result.item.reminderRevision)
        assertFalse(result.reminderChanged)
        assertNull(result.outcome)
        assertEquals("After", uc.findItem(item.id).title)
        assertEquals(now, uc.findItem(item.id).dueAt)
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test fun missingCompletedBlankAndChangedPastReminderAreRejectedWithoutSideEffects() = runTest {
        assertEquals("事项不存在", runCatching {
            uc.updateItem("missing", TodoEdit("X", null, null, null))
        }.exceptionOrNull()?.message)
        val item = uc.confirmDraft("Before", now.plusSeconds(60)).item
        for (edit in listOf(TodoEdit("  ", null, null, item.remindAt),
            TodoEdit("X", null, null, now), TodoEdit("X", null, null, now.minusSeconds(1)))) {
            assertTrue(runCatching { uc.updateItem(item.id, edit) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(item, repo.findById(item.id))
        }
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(1, scheduler.scheduled.size)
        val completed = uc.completeItem(item.id)
        val failure = runCatching { uc.updateItem(item.id, TodoEdit("X", null, null, null)) }.exceptionOrNull()
        assertEquals("已完成事项请先恢复再编辑", failure?.message)
        assertEquals(completed, repo.findById(item.id))
    }

    @Test fun failedNewScheduleStillPersistsAndReportsNotReady() = runTest {
        val item = uc.confirmDraft("Before").item
        scheduler.scheduleSucceeds = false
        val result = uc.updateItem(item.id, TodoEdit("After", null, null, now.plusSeconds(60)))
        assertEquals(ReminderOutcome.NotReady, result.outcome)
        assertEquals(1L, repo.findById(item.id)!!.reminderRevision)
    }

    @Test fun savePrecedesCancelAndScheduleAndFailedSaveHasNoSchedulerSideEffects() = runTest {
        val events = mutableListOf<String>()
        val item = TodoItem(id = "ordered", title = "Before", remindAt = now.plusSeconds(60), createdAt = now)
        var stored = item
        var failSave = false
        val orderedRepo = object : TodoRepository {
            override suspend fun all() = listOf(stored)
            override suspend fun findById(id: String) = stored
            override suspend fun delete(id: String) = Unit
            override suspend fun save(item: TodoItem) {
                if (failSave) error("disk unavailable")
                stored = item; events += "save"
            }
        }
        val orderedScheduler = object : ReminderScheduler {
            override fun cancel(itemId: String) { assertEquals(1L, stored.reminderRevision); events += "cancel" }
            override fun schedule(itemId: String, title: String, at: Instant, revision: Long): Boolean {
                assertEquals(stored.reminderRevision, revision); events += "schedule"; return true
            }
        }
        val useCases = TodoUseCases(orderedRepo, orderedScheduler, clock)
        useCases.updateItem(item.id, TodoEdit("After", null, null, now.plusSeconds(120)))
        assertEquals(listOf("save", "cancel", "schedule"), events)
        events.clear(); failSave = true
        assertNotNull(runCatching {
            useCases.updateItem(item.id, TodoEdit("X", null, null, null))
        }.exceptionOrNull())
        assertTrue(events.isEmpty())
    }
}
