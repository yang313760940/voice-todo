package com.voicetodo.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BatchDraftConfirmationTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-05T01:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `invalid final draft prevents all writes and all alarms`() = runBlocking {
        val db = database()
        try {
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy()), scheduler, FrozenClock(now), zone)
            val good = TodoDraftInput("a", TodoEdit("有效事项", null, null, now.plusSeconds(60)))
            val invalid = listOf(
                TodoDraftInput("b", TodoEdit("", null, null, null)),
                TodoDraftInput("b", TodoEdit("过去提醒", null, null, now.minusSeconds(1))),
                TodoDraftInput(
                    "b",
                    TodoEdit("错误重复", null, null, null),
                    recurrence = RecurrenceRule(
                        RecurrenceFrequency.Monthly,
                        LocalDate.of(2026, 11, 5),
                        dayOfMonth = 32,
                        reminderTimes = listOf(LocalTime.of(9, 0)),
                    ),
                ),
            )

            for (last in invalid) {
                val failure = runCatching { useCases.confirmDrafts(listOf(good, last)) }.exceptionOrNull()
                assertTrue("无效草稿必须被拒绝：$last", failure is IllegalArgumentException)
                assertTrue(useCases.listItems().isEmpty())
                assertTrue(scheduler.scheduled.isEmpty())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `database failure in the middle rolls back the whole batch and retry succeeds once`() = runBlocking {
        val db = database()
        try {
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy()), scheduler, FrozenClock(now), zone)
            useCases.listItems()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_batch BEFORE INSERT ON todos WHEN NEW.id = 'b' " +
                    "BEGIN SELECT RAISE(ABORT, 'storage failure'); END",
            )
            val drafts = listOf("a", "b", "c").map {
                TodoDraftInput(it, TodoEdit(it, null, null, now.plusSeconds(60)))
            }

            assertNotNull(runCatching { useCases.confirmDrafts(drafts) }.exceptionOrNull())
            assertTrue(useCases.listItems().isEmpty())
            assertTrue(scheduler.scheduled.isEmpty())

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_batch")
            useCases.confirmDrafts(drafts)
            useCases.confirmDrafts(drafts)
            assertEquals(3, useCases.listItems().size)
            assertEquals(3, scheduler.scheduled.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `scheduler rejection leaves the batch retryable without duplicating successful alarms`() = runBlocking {
        val db = database()
        try {
            val recorded = RecordedScheduler()
            var reject = true
            val scheduler = object : ReminderScheduler by recorded {
                override fun scheduleReminder(
                    itemId: String,
                    title: String,
                    reminderId: String,
                    at: Instant,
                    revision: Long,
                ): Boolean {
                    if (itemId == "b" && reject) throw SecurityException("permission revoked")
                    return recorded.scheduleReminder(itemId, title, reminderId, at, revision)
                }
            }
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy()), scheduler, FrozenClock(now), zone)
            val drafts = listOf("a", "b").map {
                TodoDraftInput(it, TodoEdit(it, null, null, now.plusSeconds(60)))
            }

            assertEquals(
                listOf(ReminderOutcome.Scheduled, ReminderOutcome.NotReady),
                useCases.confirmDrafts(drafts).map { it.outcome },
            )
            assertEquals(2, useCases.listItems().size)

            reject = false
            assertTrue(useCases.confirmDrafts(drafts).all { it.outcome == ReminderOutcome.Scheduled })
            assertEquals(listOf("a", "b"), recorded.scheduled.map { it.itemId })
        } finally {
            db.close()
        }
    }

    @Test
    fun `batch creates regular and recurring drafts exactly once on the current schema`() = runBlocking {
        val db = database()
        try {
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy()), scheduler, FrozenClock(now), zone)
            val rule = RecurrenceRule(
                frequency = RecurrenceFrequency.Weekly,
                startDate = LocalDate.of(2026, 11, 5),
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                reminderTimes = listOf(LocalTime.of(18, 30)),
            )
            val drafts = listOf(
                TodoDraftInput("a", TodoEdit("买菜", LocalDate.of(2026, 11, 6), null, null)),
                TodoDraftInput(
                    "b",
                    TodoEdit("锻炼", null, null, null),
                    quadrant = Quadrant.ImportantUrgent,
                    suggestedQuadrant = Quadrant.ImportantNotUrgent,
                    recurrence = rule,
                ),
                TodoDraftInput("c", TodoEdit("交报告", null, null, null), Quadrant.ImportantUrgent),
            )

            val first = useCases.confirmDrafts(drafts)
            val second = useCases.confirmDrafts(drafts)

            assertEquals(listOf(ReminderOutcome.NotRequested, ReminderOutcome.Scheduled,
                ReminderOutcome.NotRequested), first.map { it.outcome })
            assertEquals(3, second.size)
            val all = useCases.listItems()
            assertEquals(5, all.size)
            assertEquals(1, all.count { it.recurrence != null })
            val series = all.single { it.recurrence != null }
            assertEquals(rule, series.recurrence)
            assertEquals(Quadrant.ImportantUrgent, series.quadrant)
            assertEquals(Quadrant.ImportantNotUrgent, series.suggestedQuadrant)
            val occurrences = all.filter { it.seriesId == series.id }
            assertEquals(2, occurrences.size)
            assertTrue(occurrences.all { it.quadrant == Quadrant.ImportantUrgent })
            assertEquals(2, scheduler.scheduled.size)
            assertEquals(setOf("a", "c"), all.filter { it.seriesId == null && it.recurrence == null }
                .map { it.id }.toSet())
        } finally {
            db.close()
        }
    }

    @Test
    fun `undo removes the batch and generated occurrences without touching other items or trash`() = runBlocking {
        val db = database()
        try {
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy()), scheduler, FrozenClock(now), zone)
            val outside = useCases.confirmDraft(
                "本批之外",
                remindAt = now.plusSeconds(3_600),
                itemId = "outside",
            ).item
            val rule = RecurrenceRule(
                frequency = RecurrenceFrequency.Weekly,
                startDate = LocalDate.of(2026, 11, 5),
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                reminderTimes = listOf(LocalTime.of(18, 30)),
            )
            val drafts = listOf(
                TodoDraftInput("draft-a", TodoEdit("买菜", LocalDate.of(2026, 11, 6), null, null)),
                TodoDraftInput(
                    "draft-b",
                    TodoEdit("锻炼", null, null, null),
                    recurrence = rule,
                ),
            )
            val results = useCases.confirmDrafts(drafts)
            assertEquals(5, useCases.listItems().size)

            val removed = useCases.undoBatchCreation(results.map { it.item.id })

            assertEquals("普通草稿与重复模板、两次实例都应移除", 4, removed)
            assertEquals(listOf(outside.id), useCases.listItems().map { it.id })
            assertTrue(useCases.listTrash().isEmpty())
            assertTrue(scheduler.cancelled.containsAll(results.map { it.item.id }))
            assertTrue(scheduler.cancelled.none { it == outside.id })

            useCases.confirmDrafts(drafts)
            assertEquals("撤销后可以重新手动保存", 5, useCases.listItems().size)
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
