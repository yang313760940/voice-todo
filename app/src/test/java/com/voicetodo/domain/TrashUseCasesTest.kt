package com.voicetodo.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomFocusRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TrashUseCasesTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-04T09:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `deleted item leaves active views enters trash and restores its properties`() = runBlocking {
        val db = database()
        try {
            val repo = RoomTodoRepository(db, legacy())
            val scheduler = RecordedScheduler()
            val clock = FrozenClock(now)
            val useCases = TodoUseCases(repo, scheduler, clock, zone)
            val list = useCases.createList("工作")
            val item = useCases.confirmDraft(
                title = "可恢复事项",
                remindAt = now.plus(Duration.ofHours(2)),
                plannedDate = LocalDate.of(2026, 11, 5),
                dueAt = now.plus(Duration.ofDays(1)),
                quadrant = Quadrant.ImportantUrgent,
                listId = list.id,
            ).item

            useCases.deleteItem(item.id)

            assertTrue(useCases.listItems().isEmpty())
            assertNull(repo.findById(item.id))
            assertNotNull(repo.findAnyById(item.id)?.deletedAt)
            assertTrue(scheduler.cancelled.contains(item.id))
            val trashed = useCases.listTrash().single()
            assertEquals(item.id, trashed.item.id)
            assertEquals(30, trashed.daysRemaining)

            val restored = useCases.restoreDeleted(item.id)

            assertEquals(ReminderOutcome.Scheduled, restored.outcome)
            assertFalse(restored.pastReminderCleared)
            assertEquals(item.title, restored.item.title)
            assertEquals(item.plannedDate, restored.item.plannedDate)
            assertEquals(item.dueAt, restored.item.dueAt)
            assertEquals(item.quadrant, restored.item.quadrant)
            assertEquals(item.listId, restored.item.listId)
            assertNull(restored.item.deletedAt)
            assertEquals(1, useCases.listItems().size)
            assertTrue(useCases.listTrash().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `restoring after the reminder expired clears it without immediate ringing`() = runBlocking {
        val db = database()
        try {
            val repo = RoomTodoRepository(db, legacy())
            val scheduler = RecordedScheduler()
            val clock = FrozenClock(now)
            val useCases = TodoUseCases(repo, scheduler, clock, zone)
            val item = useCases.confirmDraft("过期提醒", now.plusSeconds(3600)).item
            useCases.deleteItem(item.id)
            val schedulesBeforeRestore = scheduler.scheduled.size
            clock.instant = now.plus(Duration.ofHours(2))

            val restored = useCases.restoreDeleted(item.id)

            assertEquals(ReminderOutcome.NotRequested, restored.outcome)
            assertTrue(restored.pastReminderCleared)
            assertNull(restored.item.remindAt)
            assertTrue(restored.item.reminders.isEmpty())
            assertEquals(schedulesBeforeRestore, scheduler.scheduled.size)
            assertEquals(1, useCases.listItems().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `permanent delete and 30 day cleanup remove trash without deleting focus history`() = runBlocking {
        val db = database()
        try {
            val repo = RoomTodoRepository(db, legacy())
            val scheduler = RecordedScheduler()
            val clock = FrozenClock(now)
            val useCases = TodoUseCases(repo, scheduler, clock, zone)
            val focusRepo = RoomFocusRepository(db)
            val focus = FocusUseCases(focusRepo, RecordedFocusScheduler(), clock, zone = zone)
            val item = useCases.confirmDraft("专注后删除", now.plus(Duration.ofHours(2))).item
            focus.startFocus(itemId = item.id)
            clock.instant = now.plus(Duration.ofMinutes(25))
            focus.reconcileEnd()
            focus.confirmEnded()
            assertEquals(1, focusRepo.history().size)

            useCases.deleteItem(item.id)
            useCases.permanentlyDelete(item.id)
            assertNull(repo.findAnyById(item.id))

            val expiring = useCases.confirmDraft("三十天后清理", itemId = "expiring").item
            useCases.deleteItem(expiring.id)
            clock.instant = now.plus(Duration.ofDays(31))
            assertTrue(useCases.listTrash().isEmpty())
            assertNull(repo.findAnyById(expiring.id))
            assertEquals("专注历史独立保留", 1, focusRepo.history().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting only this occurrence does not delete or regenerate future occurrences`() = runBlocking {
        val db = database()
        try {
            val repo = RoomTodoRepository(db, legacy())
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(repo, scheduler, FrozenClock(now), zone)
            val rule = RecurrenceRule(
                RecurrenceFrequency.Daily,
                LocalDate.of(2026, 11, 4),
                reminderTimes = listOf(LocalTime.of(18, 0)),
            )
            val series = useCases.createRecurring("每日打卡", rule).item
            val before = useCases.listIncomplete().sortedBy { it.occurrenceDate }
            val selected = before.first()

            useCases.deleteItem(selected.id, RecurringDeleteScope.ThisOnly)

            assertNotNull(repo.findAnyById(selected.id)?.deletedAt)
            assertTrue(scheduler.cancelled.contains(selected.id))
            val after = useCases.listIncomplete()
            assertTrue(after.none { it.occurrenceDate == selected.occurrenceDate })
            assertTrue(after.any { it.occurrenceDate == before.last().occurrenceDate })
            assertEquals(2, after.size)
            assertEquals("仅删本次不会停用系列", series.id, useCases.findItem(series.id).id)
            assertTrue(useCases.listTrash().any { it.item.id == selected.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting this and future stops the series and keeps earlier completed history`() = runBlocking {
        val db = database()
        try {
            val repo = RoomTodoRepository(db, legacy())
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(repo, scheduler, FrozenClock(now), zone)
            val rule = RecurrenceRule(
                RecurrenceFrequency.Weekly,
                LocalDate.of(2026, 11, 4),
                weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                reminderTimes = listOf(LocalTime.of(18, 0)),
            )
            val series = useCases.createRecurring("每周锻炼", rule).item
            val occurrences = useCases.listIncomplete().sortedBy { it.occurrenceDate }
            val selected = occurrences.first()

            useCases.deleteItem(selected.id, RecurringDeleteScope.ThisAndFuture)

            assertTrue(useCases.listIncomplete().none { it.seriesId == series.id })
            assertNotNull(repo.findAnyById(series.id)?.deletedAt)
            assertTrue(occurrences.all { repo.findAnyById(it.id)?.deletedAt != null })
            assertTrue(useCases.listTrash().any { it.item.id == selected.id })
        } finally {
            db.close()
        }
    }

    private fun database(): TodoDatabase {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
    }

    private fun legacy() = FileTodoRepository(temp.newFolder(), zone)
}
