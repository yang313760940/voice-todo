package com.voicetodo.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TodoUseCasesIssue4Test {

    private val now = Instant.parse("2026-11-04T09:00:00Z")
    private val repo = InMemoryTodoRepository()
    private val scheduler = RecordedScheduler()
    private val clock = FrozenClock(now)

    @Test
    fun `confirming a draft without any time saves the item and schedules nothing`() = runTest {
        val result = TodoUseCases(repo, scheduler, clock).confirmDraft(title = "买牛奶")

        val saved = repo.all().single()
        assertEquals("买牛奶", saved.title)
        assertNull(saved.remindAt)
        assertNull(saved.plannedDate)
        assertNull(saved.dueAt)
        assertNull(saved.completedAt)
        assertEquals(0L, saved.reminderRevision)
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(ReminderOutcome.NotRequested, result.outcome)
    }

    @Test
    fun `a planned date alone does not request a reminder`() = runTest {
        val plannedDate = LocalDate.of(2026, 11, 5)

        val result = TodoUseCases(repo, scheduler, clock)
            .confirmDraft(title = "整理书桌", plannedDate = plannedDate)

        val saved = repo.all().single()
        assertEquals(plannedDate, saved.plannedDate)
        assertNull(saved.remindAt)
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(ReminderOutcome.NotRequested, result.outcome)
    }

    @Test
    fun `a due time alone does not request a reminder`() = runTest {
        val dueAt = Instant.parse("2026-11-06T10:00:00Z")

        val result = TodoUseCases(repo, scheduler, clock)
            .confirmDraft(title = "提交材料", dueAt = dueAt)

        val saved = repo.all().single()
        assertEquals(dueAt, saved.dueAt)
        assertNull(saved.remindAt)
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(ReminderOutcome.NotRequested, result.outcome)
    }

    @Test
    fun `completing an item keeps it and cancels its future reminder`() = runTest {
        val useCases = TodoUseCases(repo, scheduler, clock)
        val futureReminder = now.plusSeconds(3600)
        val saved = useCases.confirmDraft(
            title = "完成但保留提醒",
            remindAt = futureReminder,
            dueAt = now.minusSeconds(60),
        ).item
        assertEquals(0L, saved.reminderRevision)
        assertEquals(0L, scheduler.scheduled.single().revision)

        val completed = useCases.completeItem(saved.id)

        val reloaded = repo.findById(saved.id)
        assertNotNull("完成事项不能被删除", reloaded)
        assertNotNull("完成时刻应被记录", completed.completedAt)
        assertEquals(futureReminder, reloaded!!.remindAt)
        assertTrue(scheduler.cancelled.contains(saved.id))
        assertEquals("完成不应产生新调度", 1, scheduler.scheduled.size)
        assertEquals(1L, completed.reminderRevision)
        assertEquals(1L, reloaded.reminderRevision)
        assertFalse(completed.isOverdue(clock.now()))
    }

    @Test
    fun `completing the same item twice keeps the first completion time`() = runTest {
        val useCases = TodoUseCases(repo, scheduler, clock)
        val saved = useCases.confirmDraft(
            title = "重复完成",
            remindAt = now.plusSeconds(3600),
        ).item
        val firstCompletedAt = useCases.completeItem(saved.id).completedAt

        clock.instant = now.plus(Duration.ofHours(1))
        val second = useCases.completeItem(saved.id)

        assertEquals(firstCompletedAt, second.completedAt)
        assertEquals(1L, second.reminderRevision)
        assertEquals(1, repo.all().size)
    }

    @Test
    fun `restoring a completed item reschedules its future reminder`() = runTest {
        val useCases = TodoUseCases(repo, scheduler, clock)
        val futureReminder = now.plusSeconds(3600)
        val saved = useCases.confirmDraft(
            title = "恢复未来提醒",
            remindAt = futureReminder,
        ).item
        assertEquals(1L, useCases.completeItem(saved.id).reminderRevision)

        val restored = useCases.restoreItem(saved.id)

        val reloaded = repo.findById(saved.id)!!
        assertNull(reloaded.completedAt)
        assertEquals(futureReminder, reloaded.remindAt)
        assertEquals(ReminderOutcome.Scheduled, restored.outcome)
        assertFalse(restored.pastReminderCleared)
        assertEquals(2L, reloaded.reminderRevision)
        assertEquals("应重新安排原未来提醒", 2, scheduler.scheduled.size)
        assertEquals(futureReminder, scheduler.scheduled.last().at)
        assertEquals(2L, scheduler.scheduled.last().revision)
        assertEquals("恢复不能复制事项", 1, repo.all().size)
    }

    @Test
    fun `restoring with an expired reminder clears it without scheduling`() = runTest {
        val useCases = TodoUseCases(repo, scheduler, clock)
        val remindAt = now.plusSeconds(60)
        val saved = useCases.confirmDraft(
            title = "过期提醒恢复",
            remindAt = remindAt,
        ).item
        assertEquals(1L, useCases.completeItem(saved.id).reminderRevision)
        clock.instant = remindAt.plusSeconds(1)
        val cancelsBefore = scheduler.cancelled.count { it == saved.id }

        val restored = useCases.restoreItem(saved.id)

        val reloaded = repo.findById(saved.id)!!
        assertNull(reloaded.completedAt)
        assertNull(reloaded.remindAt)
        assertEquals("过期提醒不能重新调度", 1, scheduler.scheduled.size)
        assertEquals(cancelsBefore + 1, scheduler.cancelled.count { it == saved.id })
        assertEquals(ReminderOutcome.NotRequested, restored.outcome)
        assertTrue(restored.pastReminderCleared)
        assertEquals(2L, reloaded.reminderRevision)
        assertEquals(1, repo.all().size)
    }

    @Test
    fun `an already restored item does not report the expired reminder again`() = runTest {
        val useCases = TodoUseCases(repo, scheduler, clock)
        val remindAt = now.plusSeconds(60)
        val saved = useCases.confirmDraft(
            title = "只提示一次",
            remindAt = remindAt,
        ).item
        useCases.completeItem(saved.id)
        clock.instant = remindAt.plusSeconds(1)
        useCases.restoreItem(saved.id)
        val scheduleCount = scheduler.scheduled.size

        val restoredAgain = useCases.restoreItem(saved.id)

        assertEquals(ReminderOutcome.NotRequested, restoredAgain.outcome)
        assertFalse(restoredAgain.pastReminderCleared)
        assertEquals(scheduleCount, scheduler.scheduled.size)
        assertEquals(1, repo.all().size)
    }
}
