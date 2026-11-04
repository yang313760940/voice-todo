package com.voicetodo.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TodoUseCasesAiDraftTest {
    private val now = Instant.parse("2026-11-04T09:00:00Z")
    private val repo = InMemoryTodoRepository()
    private val scheduler = RecordedScheduler()
    private val clock = FrozenClock(now)
    private val useCases = TodoUseCases(repo, scheduler, clock)
    private val dueAt = Instant.parse("2026-11-06T12:30:00Z")
    private val specifiedAt = Instant.parse("2026-11-05T10:15:00Z")

    @Test
    fun `confirming an AI draft saves multiple reminders and the chosen quadrant`() = runTest {
        val result = useCases.confirmDraft(
            title = " 提交季度报告 ",
            plannedDate = LocalDate.of(2026, 11, 5),
            dueAt = dueAt,
            reminders = listOf(
                ReminderEdit(id = "specified", at = specifiedAt),
                ReminderEdit(id = "relative", beforeDue = Duration.ofHours(1)),
            ),
            quadrant = Quadrant.ImportantUrgent,
            itemId = "draft-1",
        )

        val saved = repo.all().single()
        assertEquals("draft-1", saved.id)
        assertEquals("提交季度报告", saved.title)
        assertEquals(Quadrant.ImportantUrgent, saved.quadrant)
        assertEquals(2, saved.reminders.size)
        assertEquals(specifiedAt, saved.reminders[0].at)
        assertEquals(Instant.parse("2026-11-06T11:30:00Z"), saved.reminders[1].at)
        assertEquals(2, scheduler.scheduled.size)
        assertEquals(ReminderOutcome.Scheduled, result.outcome)
    }

    @Test
    fun `invalid ai fields fail before any item or alarm is written`() = runTest {
        val attempts = listOf(
            listOf(ReminderEdit(id = "past", at = now.minusSeconds(1))),
            listOf(ReminderEdit(id = "no-due", beforeDue = Duration.ofMinutes(30))),
            listOf(ReminderEdit(id = "zero", beforeDue = Duration.ZERO)),
        )
        attempts.forEach { reminders ->
            try {
                useCases.confirmDraft(
                    title = "无效草稿",
                    dueAt = null,
                    reminders = reminders,
                    itemId = "invalid-${reminders.single().id}",
                )
                fail("无效提醒必须被拒绝")
            } catch (_: IllegalArgumentException) {
            }
        }

        assertTrue(repo.all().isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `repeated confirmation of the same draft does not duplicate the item or alarms`() = runTest {
        val first = useCases.confirmDraft(
            title = "只创建一次",
            plannedDate = LocalDate.of(2026, 11, 5),
            reminders = listOf(ReminderEdit(id = "one", at = specifiedAt)),
            quadrant = Quadrant.NotImportantUrgent,
            itemId = "stable-draft",
        )
        val schedulesAfterFirst = scheduler.scheduled.size

        val second = useCases.confirmDraft(
            title = "重复点击",
            reminders = listOf(ReminderEdit(id = "one", at = specifiedAt)),
            itemId = "stable-draft",
        )

        assertEquals(1, repo.all().size)
        assertEquals(first.item.id, second.item.id)
        assertEquals("只创建一次", second.item.title)
        assertEquals(schedulesAfterFirst, scheduler.scheduled.size)
        assertEquals(Quadrant.NotImportantUrgent, second.item.quadrant)
    }

    @Test
    fun `manual creation still defaults to the not important not urgent quadrant`() = runTest {
        val result = useCases.confirmDraft(title = "手动事项")

        assertEquals(Quadrant.NotImportantNotUrgent, result.item.quadrant)
        assertEquals(ReminderOutcome.NotRequested, result.outcome)
    }
}
