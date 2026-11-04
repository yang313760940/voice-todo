package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoViewsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-05T01:00:00Z")
    private val today = LocalDate.of(2026, 11, 5)

    private fun item(
        id: String,
        plannedDate: LocalDate? = null,
        dueAt: Instant? = null,
        remindAt: Instant? = null,
        reminders: List<TodoReminder> = emptyList(),
        completedAt: Instant? = null,
        listId: String? = null,
    ) = TodoItem(
        id = id,
        title = id,
        plannedDate = plannedDate,
        dueAt = dueAt,
        remindAt = remindAt,
        reminders = reminders,
        completedAt = completedAt,
        createdAt = now,
        listId = listId,
    )

    @Test fun todayCollectsAllFourSourcesAndDeduplicatesTheSameItem() {
        val plan = item("plan", plannedDate = today)
        val due = item("due", dueAt = Instant.parse("2026-11-05T10:00:00Z"))
        val reminder = item("reminder", remindAt = Instant.parse("2026-11-05T02:00:00Z"))
        val overdue = item("overdue", dueAt = Instant.parse("2026-11-03T10:00:00Z"))
        val duplicate = item(
            "duplicate",
            plannedDate = today,
            dueAt = Instant.parse("2026-11-05T12:00:00Z"),
            remindAt = Instant.parse("2026-11-05T03:00:00Z"),
        )
        val future = item("future", plannedDate = today.plusDays(2))
        val completed = item("completed", plannedDate = today, completedAt = now)
        val items = listOf(plan, due, reminder, overdue, duplicate, future, completed)

        val result = itemsForView(items, TodoView.Today, now, zone)

        assertEquals(setOf("plan", "due", "reminder", "overdue", "duplicate"), result.map { it.id }.toSet())
        assertEquals(5, result.size)
        assertEquals("overdue", result.first().id)
        assertFalse(completed in result)
    }

    @Test fun todayUsesTheLocalCalendarDayAtBothEnds() {
        val startOfDay = item("start", remindAt = Instant.parse("2026-11-04T16:00:00Z"))
        val endOfDay = item("end", dueAt = Instant.parse("2026-11-05T15:59:59Z"))
        val nextDay = item("next", dueAt = Instant.parse("2026-11-05T16:00:00Z"))

        val result = itemsForView(
            listOf(startOfDay, endOfDay, nextDay), TodoView.Today, now, zone,
        )

        assertEquals(listOf("start", "end"), result.map { it.id })
    }

    @Test fun recentSevenDaysIsSevenConsecutiveLocalDatesStartingToday() {
        val todayItem = item("today", plannedDate = today)
        val sixth = item("sixth", plannedDate = today.plusDays(6))
        val seventh = item("seventh", plannedDate = today.plusDays(7))
        val overdue = item("overdue", dueAt = now.minusSeconds(86_400))
        val completed = item("completed", dueAt = now, completedAt = now)

        val result = itemsForView(
            listOf(todayItem, sixth, seventh, overdue, completed),
            TodoView.RecentSevenDays,
            now,
            zone,
        )

        assertEquals(setOf("today", "sixth"), result.map { it.id }.toSet())
    }

    @Test fun inboxIncludesScheduledUnclassifiedItemsAndSupportsUnscheduledFilter() {
        val scheduled = item("scheduled", plannedDate = today)
        val reminderOnly = item("reminderOnly", remindAt = Instant.parse("2026-11-05T02:00:00Z"))
        val unscheduled = item("unscheduled")
        val listed = item("listed", plannedDate = today, listId = "work")

        val inbox = itemsForView(
            listOf(scheduled, reminderOnly, unscheduled, listed), TodoView.Inbox, now, zone,
        )
        val onlyUnscheduled = itemsForView(
            listOf(scheduled, reminderOnly, unscheduled, listed),
            TodoView.Inbox,
            now,
            zone,
            unscheduledOnly = true,
        )

        assertEquals(setOf("scheduled", "reminderOnly", "unscheduled"), inbox.map { it.id }.toSet())
        assertEquals(listOf("unscheduled"), onlyUnscheduled.map { it.id })
    }

    @Test fun customListAndAllAndCompletedShareTheSameItemSet() {
        val mine = item("mine", plannedDate = today, listId = "work")
        val other = item("other")
        val completed = item("done", completedAt = now, listId = "work")
        val items = listOf(mine, other, completed)

        assertEquals(
            listOf("mine"),
            itemsForView(items, TodoView.CustomList("work"), now, zone).map { it.id },
        )
        assertEquals(
            setOf("mine", "other"),
            itemsForView(items, TodoView.All, now, zone).map { it.id }.toSet(),
        )
        assertEquals(
            listOf("done"),
            itemsForView(items, TodoView.Completed, now, zone).map { it.id },
        )
    }

    @Test fun unscheduledMeansNoPlanNoDeadlineAndNoReminder() {
        assertTrue(item("empty").isUnscheduled())
        assertFalse(item("plan", plannedDate = today).isUnscheduled())
        assertFalse(item("due", dueAt = now).isUnscheduled())
        assertFalse(item("remind", remindAt = now).isUnscheduled())
        assertFalse(
            item("multi", reminders = listOf(TodoReminder("r", now))).isUnscheduled(),
        )
    }
}
