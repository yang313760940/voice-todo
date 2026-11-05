package com.voicetodo.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 票 #8：重复事项变更的范围（仅本次 / 本次及以后）。
 * - 仅本次只改选中实例，下一实例维持旧规则。
 * - 本次及以后从选中实例起改规则并重排后续实例，历史完成/漏做记录不变。
 * - 变更持久化并可重开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecurrenceScopeTest {
    @get:Rule val temp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-04T00:00:00Z") // 08:00 上海
    private val date = LocalDate.of(2026, 11, 4)
    private val nine = LocalTime.of(9, 0)
    private val ten = LocalTime.of(10, 0)

    private suspend fun withUseCases(
        block: suspend (TodoUseCases, RecordedScheduler) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).build()
        try {
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(
                RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), zone)),
                scheduler,
                FrozenClock(now),
                zone,
            )
            block(useCases, scheduler)
        } finally {
            db.close()
        }
    }

    private suspend fun withUseCasesAndClock(
        block: suspend (TodoUseCases, RecordedScheduler, FrozenClock) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).build()
        try {
            val scheduler = RecordedScheduler()
            val clock = FrozenClock(now)
            val useCases = TodoUseCases(
                RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), zone)),
                scheduler,
                clock,
                zone,
            )
            block(useCases, scheduler, clock)
        } finally {
            db.close()
        }
    }

    private fun editFor(
        item: TodoItem,
        newTitle: String,
        newLocal: LocalDateTime,
        listId: String? = item.listId,
        updateList: Boolean = false,
    ): TodoEdit {
        val at = newLocal.atZone(zone).toInstant()
        return TodoEdit(
            title = newTitle,
            plannedDate = item.plannedDate,
            dueAt = item.dueAt,
            remindAt = at,
            reminders = listOf(ReminderEdit(item.reminders.single().id, at = at)),
            listId = listId,
            updateList = updateList,
        )
    }

    @Test fun thisOnlyEditChangesOnlyTheSelectedInstanceAndNextKeepsOldRule() = runTest {
        withUseCases { useCases, _ ->
            val series = useCases.createRecurring(
                "喝水", RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val occ = useCases.listIncomplete().filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }
            assertEquals(2, occ.size)
            val first = occ[0]
            val second = occ[1]

            useCases.updateRecurringItem(
                first.id,
                editFor(first, "喝水(改)", LocalDateTime.of(date, ten)),
                RecurringEditScope.ThisOnly,
            )

            val edited = useCases.findItem(first.id)
            assertEquals("喝水(改)", edited.title)
            assertEquals(Instant.parse("2026-11-04T02:00:00Z"), edited.reminders.single().at)

            val next = useCases.findItem(second.id)
            assertEquals("下一实例维持旧标题", "喝水", next.title)
            assertEquals(
                "下一实例维持旧提醒时刻",
                Instant.parse("2026-11-05T01:00:00Z"),
                next.reminders.single().at,
            )
            assertEquals(listOf(nine), useCases.findItem(series.id).recurrence!!.reminderTimes)
        }
    }

    @Test fun thisAndFutureEditUpdatesRuleFromSelectedAndReplansFollowingInstances() = runTest {
        withUseCases { useCases, _ ->
            val list = useCases.createList("健身")
            val series = useCases.createRecurring(
                "晨跑", RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val first = useCases.listIncomplete().filter { it.seriesId == series.id }
                .minBy { it.occurrenceDate!! }

            useCases.updateRecurringItem(
                first.id,
                editFor(first, "晨跑(改)", LocalDateTime.of(date, ten), listId = list.id, updateList = true),
                RecurringEditScope.ThisAndFuture,
            )

            val reloaded = useCases.findItem(series.id)
            assertEquals(listOf(ten), reloaded.recurrence!!.reminderTimes)
            assertEquals("晨跑(改)", reloaded.title)

            val upcoming = useCases.listIncomplete().filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }
            assertTrue("仍应有接下来两次实例", upcoming.size >= 2)
            assertTrue("选中实例仍从当天起", upcoming.first().occurrenceDate == date)
            assertTrue("后续实例全部采用新标题", upcoming.all { it.title == "晨跑(改)" })
            assertTrue("后续实例继承新清单", upcoming.all { it.listId == list.id })
            assertTrue(
                "后续实例全部采用新提醒时刻",
                upcoming.all { it.reminders.single().at.atZone(zone).toLocalTime() == ten },
            )
        }
    }

    @Test fun thisAndFutureKeepsCompletedHistoryUnchanged() = runTest {
        withUseCases { useCases, _ ->
            val series = useCases.createRecurring(
                "背单词", RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val occ = useCases.listIncomplete().filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }
            val done = occ[0]
            useCases.completeItem(done.id)
            val historyBefore = useCases.findItem(done.id)
            assertNotNull(historyBefore.completedAt)

            val target = useCases.findItem(occ[1].id)
            useCases.updateRecurringItem(
                target.id,
                editFor(target, "背单词(改)", LocalDateTime.of(LocalDate.of(2026, 11, 5), ten)),
                RecurringEditScope.ThisAndFuture,
            )

            val historyAfter = useCases.findItem(done.id)
            assertEquals("历史完成实例标题不变", "背单词", historyAfter.title)
            assertEquals("历史完成时刻不变", historyBefore.completedAt, historyAfter.completedAt)
            assertEquals("历史提醒不变", historyBefore.reminders, historyAfter.reminders)

            val upcoming = useCases.listIncomplete().filter { it.seriesId == series.id }
            assertFalse("已完成实例不再出现在未完成列表", upcoming.any { it.id == done.id })
            assertTrue("后续实例全部采用新标题", upcoming.all { it.title == "背单词(改)" })
        }
    }

    @Test fun thisAndFutureEditSurvivesDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = temp.newFile("scope.db")
        val legacy = FileTodoRepository(temp.newFolder(), zone)
        fun open() = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath).build()

        var db = open()
        val seriesId = try {
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy), RecordedScheduler(), FrozenClock(now), zone)
            val series = useCases.createRecurring(
                "写日记", RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val first = useCases.listIncomplete().filter { it.seriesId == series.id }
                .minBy { it.occurrenceDate!! }
            useCases.updateRecurringItem(
                first.id,
                editFor(first, "写日记(改)", LocalDateTime.of(date, ten)),
                RecurringEditScope.ThisAndFuture,
            )
            series.id
        } finally {
            db.close()
        }

        db = open()
        try {
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy), RecordedScheduler(), FrozenClock(now), zone)
            val series = useCases.findItem(seriesId)
            assertEquals(listOf(ten), series.recurrence!!.reminderTimes)
            assertEquals("写日记(改)", series.title)
            val upcoming = useCases.listIncomplete().filter { it.seriesId == seriesId }
            assertTrue(
                "重开后后续实例仍是新标题与新时刻",
                upcoming.all { it.title == "写日记(改)" && it.reminders.single().at.atZone(zone).toLocalTime() == ten },
            )
        } finally {
            db.close()
        }
    }

    @Test fun stopRecurrenceRemovesFutureInstancesKeepsHistoryAndIgnoresStaleCallbacks() = runTest {
        withUseCases { useCases, scheduler ->
            val series = useCases.createRecurring(
                "长跑", RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val occ = useCases.listIncomplete().filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }
            val done = occ[0]
            val future = occ[1]
            useCases.completeItem(done.id)
            val reminderId = future.reminders.single().id
            val atMillis = future.reminders.single().at.toEpochMilli()
            val revision = future.reminders.single().revision

            useCases.stopRecurrence(done.id)

            assertNotNull("历史仍可查看", useCases.findItem(done.id).completedAt)
            assertTrue("系列模板已移除", useCases.listItems().none { it.id == series.id })
            assertTrue("后续实例的闹钟已撤销", scheduler.pending.keys.none { it.first == future.id })
            assertFalse(
                "结束安排后旧回调不再触发",
                useCases.deliverReminder(future.id, reminderId, revision, atMillis) { true },
            )
        }
    }

    @Test fun thisAndFutureOnPastInstanceKeepsMissedHistoryBetweenSelectionAndToday() = runTest {
        withUseCasesAndClock { useCases, _, clock ->
            clock.instant = Instant.parse("2026-11-01T00:00:00Z") // 11-01 08:00 上海
            val series = useCases.createRecurring(
                "晨练",
                RecurrenceRule(RecurrenceFrequency.Daily, LocalDate.of(2026, 11, 1), reminderTimes = listOf(nine)),
            ).item
            clock.instant = Instant.parse("2026-11-04T00:00:00Z") // 11-04 08:00 上海
            useCases.listIncomplete() // 触发从今天起重排：保留 11-01/11-02，新增 11-04/11-05

            val selected = useCases.findItem("${series.id}@2026-11-01")
            val missed = useCases.findItem("${series.id}@2026-11-02")
            // 只改标题，保持过去提醒时刻不变（过去时刻不能改成新的过去时刻）。
            val edit = TodoEdit(
                title = "晨练(改)",
                plannedDate = selected.plannedDate,
                dueAt = selected.dueAt,
                remindAt = selected.remindAt,
                reminders = null,
                listId = selected.listId,
                updateList = false,
            )
            useCases.updateRecurringItem(selected.id, edit, RecurringEditScope.ThisAndFuture)

            val missedAfter = useCases.findItem(missed.id)
            assertEquals("选中与今天之间错过的记录必须保留", "晨练", missedAfter.title)
            assertEquals(listOf(nine), missedAfter.reminders.map { it.at.atZone(zone).toLocalTime() })
            assertEquals("晨练(改)", useCases.findItem(selected.id).title)
            val future = useCases.listIncomplete().filter {
                it.seriesId == series.id && it.occurrenceDate!! >= LocalDate.of(2026, 11, 4)
            }
            assertTrue("今天及以后采用新标题", future.all { it.title == "晨练(改)" })
        }
    }
}
