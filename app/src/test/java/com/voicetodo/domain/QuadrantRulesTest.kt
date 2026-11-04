package com.voicetodo.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadrantRulesTest {
    private val now = Instant.parse("2026-11-05T01:00:00Z")
    private val repo = InMemoryTodoRepository()
    private val scheduler = RecordedScheduler()
    private val useCases = TodoUseCases(repo, scheduler, FrozenClock(now))

    private fun item(
        title: String,
        quadrant: Quadrant,
        dueAt: Instant? = null,
        remindedAt: Instant? = null,
        createdAt: Instant = now,
        suggestedQuadrant: Quadrant? = null,
    ) = TodoItem(
        id = UUID.randomUUID().toString(),
        title = title,
        dueAt = dueAt,
        createdAt = createdAt,
        quadrant = quadrant,
        suggestedQuadrant = suggestedQuadrant,
        reminders = listOfNotNull(remindedAt?.let { TodoReminder(id = "r-$title", at = it) }),
    )

    @Test
    fun `moving quadrants changes only the category and never reschedules`() = runTest {
        val created = useCases.confirmDraft(
            title = "写周报",
            remindAt = now.plus(Duration.ofHours(2)),
            dueAt = now.plus(Duration.ofHours(6)),
            quadrant = Quadrant.NotImportantNotUrgent,
        )
        scheduler.scheduled.clear()
        scheduler.cancelled.clear()

        val moved = useCases.moveToQuadrant(created.item.id, Quadrant.ImportantUrgent)

        assertEquals(Quadrant.ImportantUrgent, moved.quadrant)
        assertEquals(created.item.remindAt, moved.remindAt)
        assertEquals(created.item.dueAt, moved.dueAt)
        assertEquals(created.item.plannedDate, moved.plannedDate)
        assertEquals(created.item.reminders, moved.reminders)
        assertEquals(created.item.reminderRevision, moved.reminderRevision)
        assertTrue("移动象限不应重新调度提醒", scheduler.scheduled.isEmpty())
        assertTrue("移动象限不应取消提醒", scheduler.cancelled.isEmpty())
        assertEquals(Quadrant.ImportantUrgent, repo.findById(created.item.id)?.quadrant)
    }

    @Test
    fun `manual items default to not important not urgent while AI suggestions stay advisory`() = runTest {
        val manual = useCases.confirmDraft(title = "手动创建")
        assertEquals(Quadrant.NotImportantNotUrgent, manual.item.quadrant)
        assertEquals(null, manual.item.suggestedQuadrant)

        val ai = useCases.confirmDraft(
            title = "AI 草稿",
            quadrant = Quadrant.ImportantNotUrgent,
            suggestedQuadrant = Quadrant.ImportantUrgent,
        )
        assertEquals(Quadrant.ImportantNotUrgent, ai.item.quadrant)
        assertEquals(Quadrant.ImportantUrgent, ai.item.suggestedQuadrant)

        val moved = useCases.moveToQuadrant(ai.item.id, Quadrant.NotImportantUrgent)
        assertEquals(Quadrant.NotImportantUrgent, moved.quadrant)
        assertEquals("移动后仍保留原始 AI 建议", Quadrant.ImportantUrgent, moved.suggestedQuadrant)
    }

    @Test
    fun `each quadrant lists overdue first then near then the rest`() {
        val overdue = item("逾期", Quadrant.ImportantUrgent, dueAt = now.minus(Duration.ofHours(1)))
        val nearDue = item("临近截止", Quadrant.ImportantUrgent, dueAt = now.plus(Duration.ofMinutes(30)))
        val nearReminder = item(
            "临近提醒",
            Quadrant.ImportantUrgent,
            remindedAt = now.plus(Duration.ofHours(2)),
        )
        val normal = item("普通", Quadrant.ImportantUrgent, dueAt = now.plus(Duration.ofDays(3)))
        val otherQuadrant = item("另一象限", Quadrant.ImportantNotUrgent, dueAt = now.minus(Duration.ofHours(2)))

        val entries = quadrantEntries(
            listOf(normal, nearReminder, overdue, otherQuadrant, nearDue),
            Quadrant.ImportantUrgent,
            now,
        )

        assertEquals(listOf("逾期", "临近截止", "临近提醒", "普通"), entries.map { it.item.title })
        assertEquals(
            listOf(QuadrantUrgency.Overdue, QuadrantUrgency.Near, QuadrantUrgency.Near, QuadrantUrgency.Normal),
            entries.map { it.urgency },
        )
    }

    @Test
    fun `near deadlines and reminders only add hints and never change the quadrant`() {
        val near = item(
            "临近",
            Quadrant.NotImportantNotUrgent,
            dueAt = now.plus(Duration.ofMinutes(45)),
            remindedAt = now.plus(Duration.ofHours(3)),
        )

        assertTrue(near.hasNearDeadline(now))
        assertTrue(near.hasNearReminder(now))
        assertEquals(Quadrant.NotImportantNotUrgent, near.quadrant)
        assertEquals(QuadrantUrgency.Near, near.quadrantUrgency(now))

        val far = item("远期", Quadrant.NotImportantNotUrgent, dueAt = now.plus(Duration.ofHours(25)))
        assertFalse(far.hasNearDeadline(now))
        assertEquals(QuadrantUrgency.Normal, far.quadrantUrgency(now))
    }
}
