package com.voicetodo.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MultipleRemindersTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-04T09:00:00Z")

    private suspend fun withUseCases(block: suspend (TodoUseCases, RecordedScheduler, FrozenClock) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).build()
        try {
            val scheduler = RecordedScheduler()
            val clock = FrozenClock(now)
            val useCases = TodoUseCases(RoomTodoRepository(db,
                FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))), scheduler, clock)
            block(useCases, scheduler, clock)
        } finally { db.close() }
    }

    @Test fun twoSpecifiedTimesAndARelativeRuleSurviveDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = temp.newFile("multiple.db")
        val legacy = FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))
        fun open() = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath).build()
        var db = open()
        try {
            var useCases = TodoUseCases(RoomTodoRepository(db, legacy), RecordedScheduler(), FrozenClock(now))
            val due = Instant.parse("2026-11-04T14:00:00Z")
            val item = useCases.confirmDraft("提交报告", dueAt = due).item
            useCases.updateItem(item.id, TodoEdit(item.title, null, due, null, reminders = listOf(
                ReminderEdit(id = "first", at = Instant.parse("2026-11-04T10:00:00Z")),
                ReminderEdit(id = "second", at = Instant.parse("2026-11-04T11:00:00Z")),
                ReminderEdit(id = "before-due", beforeDue = Duration.ofHours(1)),
            )))
            db.close()
            db = open()
            useCases = TodoUseCases(RoomTodoRepository(db, legacy), RecordedScheduler(), FrozenClock(now))
            assertEquals(listOf(
                Triple("first", Instant.parse("2026-11-04T10:00:00Z"), null),
                Triple("second", Instant.parse("2026-11-04T11:00:00Z"), null),
                Triple("before-due", Instant.parse("2026-11-04T13:00:00Z"), Duration.ofHours(1)),
            ), useCases.findItem(item.id).reminders.map { Triple(it.id, it.at, it.beforeDue) })
        } finally {
            db.close()
        }
    }

    @Test fun changingDeadlineMovesOnlyRelativeReminders() = runTest {
        withUseCases { useCases, _, _ ->
            val due = Instant.parse("2026-11-04T14:00:00Z")
            val item = useCases.confirmDraft("提交报告", dueAt = due).item
            val saved = useCases.updateItem(item.id, TodoEdit(item.title, null, due, null, reminders = listOf(
                ReminderEdit(id = "first", at = Instant.parse("2026-11-04T10:00:00Z")),
                ReminderEdit(id = "second", at = Instant.parse("2026-11-04T11:00:00Z")),
                ReminderEdit(id = "relative", beforeDue = Duration.ofHours(1)),
            ))).item
            useCases.updateItem(item.id, TodoEdit(saved.title, saved.plannedDate,
                Instant.parse("2026-11-04T15:00:00Z"), saved.remindAt))
            assertEquals(listOf(
                "first" to Instant.parse("2026-11-04T10:00:00Z"),
                "second" to Instant.parse("2026-11-04T11:00:00Z"),
                "relative" to Instant.parse("2026-11-04T14:00:00Z"),
            ), useCases.findItem(item.id).reminders.map { it.id to it.at })
        }
    }

    @Test fun anInvalidSecondReminderRejectsTheWholeEditWithoutChangingTheItem() = runTest {
        withUseCases { useCases, _, _ ->
            val item = useCases.confirmDraft("原事项").item
            val failure = runCatching {
                useCases.updateItem(item.id, TodoEdit("不能部分保存", null, null, null, reminders = listOf(
                    ReminderEdit(id = "valid", at = Instant.parse("2026-11-04T10:00:00Z")),
                    ReminderEdit(id = "past", at = Instant.parse("2026-11-04T08:00:00Z")),
                )))
            }.exceptionOrNull()
            assertTrue("过去的第二个提醒必须要求修正", failure is IllegalArgumentException)
            assertEquals(item, useCases.findItem(item.id))
        }
    }

    @Test fun remindersAtTheSameInstantHaveIndependentSystemSchedules() = runTest {
        withUseCases { useCases, scheduler, _ ->
            val item = useCases.confirmDraft("同时提醒").item
            val at = Instant.parse("2026-11-04T10:00:00Z")
            useCases.updateItem(item.id, TodoEdit(item.title, null, null, null, reminders = listOf(
                ReminderEdit(id = "first", at = at), ReminderEdit(id = "second", at = at),
            )))
            assertEquals(setOf("first" to at, "second" to at),
                scheduler.pending.values.map { it.reminderId to it.at }.toSet())
        }
    }

    @Test fun partialScheduleFailureSurvivesReopenAndRetryDoesNotRescheduleSuccessfulReminder() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = temp.newFile("retry.db")
        val legacy = FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))
        val scheduler = RecordedScheduler().apply { rejectedReminderIds += "second" }
        fun open() = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath).build()
        var db = open()
        try {
            var useCases = TodoUseCases(RoomTodoRepository(db, legacy), scheduler, FrozenClock(now))
            val item = useCases.confirmDraft("部分失败").item
            val edit = TodoEdit(item.title, null, null, null, reminders = listOf(
                ReminderEdit(id = "first", at = Instant.parse("2026-11-04T10:00:00Z")),
                ReminderEdit(id = "second", at = Instant.parse("2026-11-04T11:00:00Z")),
            ))
            assertEquals(ReminderOutcome.NotReady, useCases.updateItem(item.id, edit).outcome)
            db.close()
            db = open()
            useCases = TodoUseCases(RoomTodoRepository(db, legacy), scheduler, FrozenClock(now))
            val persisted = useCases.findItem(item.id)
            assertEquals(listOf(ReminderOutcome.Scheduled, ReminderOutcome.NotReady),
                persisted.reminders.map { it.outcome })
            scheduler.rejectedReminderIds.clear()
            assertEquals(ReminderOutcome.Scheduled, useCases.retryReminders(item.id).outcome)
            useCases.updateItem(item.id, edit)
            useCases.retryReminders(item.id)
            assertEquals(persisted.reminders.map { it.id to it.revision },
                useCases.findItem(item.id).reminders.map { it.id to it.revision })
            assertEquals(1, scheduler.scheduled.count { it.reminderId == "first" })
            assertEquals(setOf("first", "second"), scheduler.pending.values.map { it.reminderId }.toSet())
            assertTrue(useCases.findItem(item.id).reminders.all { it.outcome == ReminderOutcome.Scheduled })
        } finally { db.close() }
    }

    @Test fun ambiguousDuplicateAndInvalidRelativeRulesNeverReplaceTheOriginalItem() = runTest {
        withUseCases { useCases, scheduler, _ ->
            val item = useCases.confirmDraft("保留原事项").item
            val at = Instant.parse("2026-11-04T10:00:00Z")
            val due = Instant.parse("2026-11-04T14:00:00Z")
            val invalid = listOf(
                TodoEdit("错误", null, due, null, listOf(ReminderEdit("same", at), ReminderEdit("same", at))),
                TodoEdit("错误", null, due, null, listOf(ReminderEdit("both", at, Duration.ofHours(1)))),
                TodoEdit("错误", null, due, null, listOf(ReminderEdit("negative", beforeDue = Duration.ofMinutes(-1)))),
                TodoEdit("错误", null, due, null, listOf(ReminderEdit("zero", beforeDue = Duration.ZERO))),
                TodoEdit("错误", null, null, null, listOf(ReminderEdit("no-deadline", beforeDue = Duration.ofHours(1)))),
            )
            for (edit in invalid) {
                assertTrue("无效规则必须要求修正: ${edit.reminders}",
                    runCatching { useCases.updateItem(item.id, edit) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(item, useCases.findItem(item.id))
            }
            assertTrue(scheduler.pending.isEmpty())
        }
    }

    @Test fun lateSimultaneousRemindersPublishIndependentlyOnceAndFailedPublicationCanRetry() = runTest {
        withUseCases { useCases, _, clock ->
            val item = useCases.confirmDraft("旧标题").item
            val at = Instant.parse("2026-11-04T10:00:00Z")
            val saved = useCases.updateItem(item.id, TodoEdit("当前标题", null, null, null, reminders = listOf(
                ReminderEdit("first", at), ReminderEdit("second", at),
            ))).item
            clock.instant = Instant.parse("2026-11-04T10:00:20Z")
            val first = saved.reminders[0]
            val second = saved.reminders[1]
            val published = mutableListOf<String>()
            assertFalse(useCases.deliverReminder(item.id, first.id, first.revision, at.toEpochMilli()) { false })
            assertFalse(useCases.wasDelivered(useCases.findItem(item.id)))
            assertFalse(useCases.deliverReminder(item.id, first.id, first.revision - 1, at.toEpochMilli()) {
                fail("旧修订不能投递"); true
            })
            assertTrue(useCases.deliverReminder(item.id, first.id, first.revision, at.toEpochMilli()) {
                published += "first:${it.title}"; true
            })
            assertFalse(useCases.deliverReminder(item.id, first.id, first.revision, at.toEpochMilli()) {
                fail("迟到的重复回调不能再次投递"); true
            })
            assertTrue(useCases.deliverReminder(item.id, second.id, second.revision, at.toEpochMilli()) {
                published += "second:${it.title}"; true
            })
            assertEquals(listOf("first:当前标题", "second:当前标题"), published)
            assertTrue(useCases.wasDelivered(useCases.findItem(item.id)))
        }
    }

    @Test fun restoringACompletedItemRearmsEachFutureReminderWithANewRevision() = runTest {
        withUseCases { useCases, scheduler, _ ->
            val item = useCases.confirmDraft("恢复多提醒").item
            val saved = useCases.updateItem(item.id, TodoEdit(item.title, null, null, null, listOf(
                ReminderEdit("first", Instant.parse("2026-11-04T10:00:00Z")),
                ReminderEdit("second", Instant.parse("2026-11-04T11:00:00Z")),
            ))).item
            useCases.completeItem(item.id)
            assertTrue(scheduler.pending.isEmpty())
            useCases.restoreItem(item.id)
            val restored = useCases.findItem(item.id)
            assertEquals(setOf("first", "second"), scheduler.pending.values.map { it.reminderId }.toSet())
            assertTrue(restored.reminders.zip(saved.reminders).all { (new, old) -> new.revision > old.revision })
            for (old in saved.reminders) {
                assertFalse(useCases.deliverReminder(item.id, old.id, old.revision, old.at.toEpochMilli()) {
                    fail("完成前的回调不能在恢复后复活"); true
                })
            }
        }
    }

    @Test fun existingReopenReconciliationKeepsFutureRemindersAfterAnEarlierReminderWasDelivered() = runTest {
        withUseCases { useCases, scheduler, clock ->
            val item = useCases.confirmDraft("重开后仍有下一提醒").item
            val saved = useCases.updateItem(item.id, TodoEdit(item.title, null, null, null, listOf(
                ReminderEdit("first", Instant.parse("2026-11-04T10:00:00Z")),
                ReminderEdit("second", Instant.parse("2026-11-04T11:00:00Z")),
            ))).item
            clock.instant = Instant.parse("2026-11-04T10:00:20Z")
            val first = saved.reminders[0]
            assertTrue(useCases.deliverReminder(item.id, first.id, first.revision, first.at.toEpochMilli()) { true })
            scheduler.pending.clear()
            useCases.reconcileReminders()
            useCases.reconcileReminders()
            assertEquals(setOf("second"), scheduler.pending.values.map { it.reminderId }.toSet())
            assertEquals(saved.reminders[1].revision, scheduler.pending.values.single().revision)
        }
    }
}
