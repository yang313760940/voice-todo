package com.voicetodo.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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

    @Test fun `invalid final draft prevents all writes and all alarms`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        try {
            val scheduler = RecordedScheduler()
            val uc = TodoUseCases(RoomTodoRepository(db,
                FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))), scheduler, FrozenClock(now))
            val good = TodoDraftInput("a", TodoEdit("有效事项", null, null, now.plusSeconds(60)))
            val invalid = listOf(
                TodoDraftInput("b", TodoEdit("", null, null, null)),
                TodoDraftInput("b", TodoEdit("过去提醒", null, null, now.minusSeconds(1))),
                TodoDraftInput("b", TodoEdit("错误重复", null, null, null),
                    recurrence = RecurrenceRule(RecurrenceFrequency.Monthly, monthDay = 32)),
            )
            for (last in invalid) {
                assertTrue(runCatching { uc.confirmDrafts(listOf(good, last)) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(uc.listItems().isEmpty())
                assertTrue(scheduler.scheduled.isEmpty())
            }
        } finally { db.close() }
    }

    @Test fun `database failure in the middle rolls back the entire batch and retry succeeds`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        try {
            val scheduler = RecordedScheduler()
            val uc = TodoUseCases(RoomTodoRepository(db,
                FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))), scheduler, FrozenClock(now))
            uc.listItems() // 初始化真实数据库，再模拟存储故障。
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_batch BEFORE INSERT ON todos WHEN NEW.id = 'b' BEGIN SELECT RAISE(ABORT, 'storage failure'); END")
            val drafts = listOf("a", "b", "c").map {
                TodoDraftInput(it, TodoEdit(it, null, null, now.plusSeconds(60)))
            }
            assertNotNull(runCatching { uc.confirmDrafts(drafts) }.exceptionOrNull())
            assertTrue(uc.listItems().isEmpty())
            assertTrue(scheduler.scheduled.isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_batch")
            uc.confirmDrafts(drafts)
            uc.confirmDrafts(drafts)
            assertEquals(3, uc.listItems().size)
            assertEquals(3, scheduler.scheduled.size)
        } finally { db.close() }
    }

    @Test fun `scheduler rejection keeps the whole batch retryable without duplicating successful alarms`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        try {
            val recorded = RecordedScheduler()
            var reject = true
            val scheduler = object : ReminderScheduler by recorded {
                override fun scheduleReminder(itemId: String, title: String, reminderId: String,
                    at: Instant, revision: Long): Boolean {
                    if (itemId == "b" && reject) throw SecurityException("permission revoked")
                    return recorded.scheduleReminder(itemId, title, reminderId, at, revision)
                }
            }
            val uc = TodoUseCases(RoomTodoRepository(db,
                FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))), scheduler, FrozenClock(now))
            val drafts = listOf("a", "b").map {
                TodoDraftInput(it, TodoEdit(it, null, null, now.plusSeconds(60)))
            }
            assertEquals(listOf(ReminderOutcome.Scheduled, ReminderOutcome.NotReady),
                uc.confirmDrafts(drafts).map { it.outcome })
            assertEquals(2, uc.listItems().size)
            reject = false
            assertTrue(uc.confirmDrafts(drafts).all { it.outcome == ReminderOutcome.Scheduled })
            assertEquals(listOf("a", "b"), recorded.scheduled.map { it.itemId })
        } finally { db.close() }
    }

    @Test fun `batch creates all drafts exactly once and retains recurring suggestions after reopening`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val path = temp.newFile("batch.db").absolutePath
        val legacy = FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))
        fun open() = Room.databaseBuilder(app, TodoDatabase::class.java, path).build()
        var db = open()
        try {
            val scheduler = RecordedScheduler()
            val uc = TodoUseCases(RoomTodoRepository(db, legacy), scheduler, FrozenClock(now))
            val drafts = listOf(
                TodoDraftInput("a", TodoEdit("买菜", java.time.LocalDate.of(2026, 11, 6), null, null)),
                TodoDraftInput("b", TodoEdit("锻炼", null, null, now.plusSeconds(60)),
                    recurrence = RecurrenceRule(RecurrenceFrequency.Weekly, setOf(1, 3, 5))),
                TodoDraftInput("c", TodoEdit("交报告", null, null, null), Quadrant.ImportantUrgent),
            )
            uc.confirmDrafts(drafts)
            uc.confirmDrafts(drafts)
            assertEquals(3, uc.listItems().size)
            assertEquals(1, scheduler.scheduled.size)
            db.close()
            db = open()
            val reopened = TodoUseCases(RoomTodoRepository(db, legacy), scheduler, FrozenClock(now))
            assertEquals(setOf(1, 3, 5), reopened.findItem("b").recurrence!!.weekdays)
            assertEquals(Quadrant.ImportantUrgent, reopened.findItem("c").quadrant)
        } finally { db.close() }
    }
}
