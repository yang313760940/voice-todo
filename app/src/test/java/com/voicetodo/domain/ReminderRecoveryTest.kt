package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRecoveryTest {
    private val now = Instant.parse("2026-11-04T00:00:00Z")

    @Test fun repeatedReconcileRestoresOnlyFutureActiveRemindersWithoutDuplicatePendingEvents() = runTest {
        val repo = InMemoryTodoRepository()
        val scheduler = RecordedScheduler()
        val clock = FrozenClock(now)
        val useCases = TodoUseCases(repo, scheduler, clock, ZoneId.of("Asia/Shanghai"))
        val active = useCases.confirmDraft("仍有效", now.plusSeconds(3600)).item
        val completed = useCases.confirmDraft("已完成", now.plusSeconds(7200)).item
        val dismissed = useCases.confirmDraft("已关闭", now.plusSeconds(10800)).item
        useCases.completeItem(completed.id)
        useCases.dismissReminder(dismissed.id)
        scheduler.pending.clear()

        useCases.reconcileReminders()
        useCases.reconcileReminders()

        assertEquals(setOf(active.id to active.id), scheduler.pending.keys)
        assertFalse(scheduler.pending.keys.any { it.first == completed.id })
        assertFalse(scheduler.pending.keys.any { it.first == dismissed.id })
    }

    @Test fun missedSummaryKeepsOriginalTimesAndExcludesDeliveredDismissedAndCompletedReminders() = runTest {
        val repo = InMemoryTodoRepository()
        val useCases = TodoUseCases(repo, RecordedScheduler(), FrozenClock(now), ZoneId.of("UTC"))
        val past = now.minusSeconds(60)
        repo.save(TodoItem(
            id = "missed", title = "错过", remindAt = past, createdAt = past.minusSeconds(60),
            reminders = listOf(TodoReminder("missed-r", past, outcome = ReminderOutcome.Scheduled)),
        ))
        repo.save(TodoItem(
            id = "delivered", title = "已投递", remindAt = past, createdAt = past.minusSeconds(60),
            reminders = listOf(TodoReminder("delivered-r", past, outcome = ReminderOutcome.Scheduled,
                deliveredAt = past.plusSeconds(1))),
        ))
        repo.save(TodoItem(
            id = "dismissed", title = "已关闭", remindAt = past, createdAt = past.minusSeconds(60),
            reminders = listOf(TodoReminder("dismissed-r", past, outcome = ReminderOutcome.NotRequested)),
        ))
        repo.save(TodoItem(
            id = "completed", title = "已完成", remindAt = past, completedAt = now,
            createdAt = past.minusSeconds(60),
            reminders = listOf(TodoReminder("completed-r", past, outcome = ReminderOutcome.Scheduled)),
        ))

        val missed = useCases.listMissedReminders()

        assertEquals(listOf("missed-r"), missed.map { it.reminderId })
        assertEquals(past, missed.single().originalAt)
    }

    @Test fun timeZoneChangeRebasesRecurringLocalTimesButKeepsOneShotInstant() = runTest {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val repo = InMemoryTodoRepository()
        val scheduler = RecordedScheduler()
        val useCases = TodoUseCases(repo, scheduler, FrozenClock(now), shanghai)
        val oneShotAt = Instant.parse("2026-11-04T04:30:00Z")
        val oneShot = useCases.confirmDraft("一次性", oneShotAt).item
        val series = useCases.createRecurring(
            "每天九点",
            RecurrenceRule(
                RecurrenceFrequency.Daily,
                LocalDate.of(2026, 11, 4),
                reminderTimes = listOf(LocalTime.of(9, 0)),
            ),
        ).item

        useCases.reconcileReminders(currentZone = losAngeles)

        assertEquals(oneShotAt, useCases.findItem(oneShot.id).remindAt)
        val occurrences = useCases.listIncomplete().filter { it.seriesId == series.id }
        assertTrue(occurrences.isNotEmpty())
        assertTrue(occurrences.all { occurrence ->
            occurrence.reminders.all { it.at.atZone(losAngeles).toLocalTime() == LocalTime.of(9, 0) }
        })
        useCases.reconcileReminders(previousZone = losAngeles, currentZone = losAngeles)
        assertEquals(occurrences.map { it.id }.toSet(),
            useCases.listIncomplete().filter { it.seriesId == series.id }.map { it.id }.toSet())
    }
}
