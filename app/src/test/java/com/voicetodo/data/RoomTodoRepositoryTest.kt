package com.voicetodo.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomTodoRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-04T09:00:00Z")

    @Test fun editingDatesAndCompletionSurviveClosingAndReopeningDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = temp.newFile("todos.db")
        val legacy = temp.newFolder()
        fun open() = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath).build()
        var db = open()
        try {
            var repo = RoomTodoRepository(db, FileTodoRepository(legacy, ZoneId.of("Asia/Shanghai")))
            var uc = TodoUseCases(repo, RecordedScheduler(), FrozenClock(now))
            val item = uc.confirmDraft("Initial").item
            uc.updateItem(item.id, TodoEdit("Final", LocalDate.of(2026, 11, 5), now, now.plusSeconds(60)))
            uc.completeItem(item.id)
            assertTrue(repo.incomplete().isEmpty())
            assertEquals(item.id, repo.completed().single().id)
            db.close()
            db = open()
            repo = RoomTodoRepository(db, FileTodoRepository(legacy, ZoneId.of("Asia/Shanghai")))
            uc = TodoUseCases(repo, RecordedScheduler(), FrozenClock(now))
            val completed = repo.completed().single()
            assertEquals("Final", completed.title)
            assertEquals(LocalDate.of(2026, 11, 5), completed.plannedDate)
            assertEquals(now, completed.dueAt)
            assertEquals(now, completed.completedAt)
            assertEquals(2L, completed.reminderRevision)
            assertEquals(ReminderOutcome.Scheduled, uc.restoreItem(item.id).outcome)
            assertNull(repo.incomplete().single().completedAt)
        } finally { db.close() }
    }

    private suspend fun databaseTest(block: suspend (TodoDatabase, RoomTodoRepository, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).build()
        val dir = temp.newFolder()
        try { block(db, RoomTodoRepository(db, FileTodoRepository(dir, ZoneId.of("Asia/Shanghai"))), dir) }
        finally { db.close() }
    }

    @Test fun explicitQueriesHaveDeterministicReminderAndCompletionOrdering() = runTest {
        databaseTest { _, repo, _ ->
            for (item in listOf(
                TodoItem(id = "plain-old", title = "P", createdAt = now),
                TodoItem(id = "plain-new", title = "P", createdAt = now.plusSeconds(1)),
                TodoItem(id = "b", title = "R", createdAt = now, remindAt = now.plusSeconds(5)),
                TodoItem(id = "a", title = "R", createdAt = now, remindAt = now.plusSeconds(5)),
                TodoItem(id = "new", title = "R", createdAt = now.plusSeconds(1), remindAt = now.plusSeconds(5)),
                TodoItem(id = "early", title = "R", createdAt = now, remindAt = now.plusSeconds(4)),
                TodoItem(id = "done-b", title = "D", createdAt = now, completedAt = now),
                TodoItem(id = "done-a", title = "D", createdAt = now, completedAt = now),
                TodoItem(id = "done-new", title = "D", createdAt = now, completedAt = now.plusNanos(1)),
            )) repo.save(item)
            assertEquals(listOf("early", "new", "a", "b", "plain-new", "plain-old"), repo.incomplete().map { it.id })
            assertEquals(listOf("done-new", "done-a", "done-b"), repo.completed().map { it.id })
        }
    }

    @Test fun legacyMigrationUsesExplicitZonePreservesFileAndIsIdempotentAfterEditsAndDeletion() = runTest {
        databaseTest { db, repo, dir ->
            val original = """[{"id":"legacy","title":"Old","createdAt":"2026-11-04T08:00:00","remindAt":"2026-11-05T09:00:00"}]"""
            val file = File(dir, "todos.json").apply { writeText(original) }
            val item = repo.incomplete().single()
            assertEquals(Instant.parse("2026-11-04T00:00:00Z"), item.createdAt)
            assertEquals(Instant.parse("2026-11-05T01:00:00Z"), item.remindAt)
            assertEquals(0L, item.reminderRevision)
            repo.save(item.copy(title = "Edited"))
            val reopened = RoomTodoRepository(db, FileTodoRepository(dir, ZoneId.of("UTC")))
            assertEquals("Edited", reopened.all().single().title)
            reopened.delete(item.id)
            val again = RoomTodoRepository(db, FileTodoRepository(dir, ZoneId.of("UTC")))
            assertTrue(again.all().isEmpty())
            assertEquals(original, file.readText())
        }
    }

    @Test fun invalidOrConflictingMigrationRollsBackAndCanBeRetriedWithoutLosingOriginal() = runTest {
        databaseTest { _, repo, dir ->
            val valid = """{"id":"first","title":"A","createdAt":"2026-11-04T08:00:00Z"}"""
            val duplicate = "[$valid,$valid]"
            val file = File(dir, "todos.json")
            for (invalid in listOf("[$valid,{\"id\":\"broken\"}]", duplicate)) {
                file.writeText(invalid)
                assertTrue(runCatching { repo.incomplete() }.exceptionOrNull() is TodoStorageException)
                assertTrue(repo.state.value is StorageState.Failed)
                assertEquals(invalid, file.readText())
            }
            file.writeText("[$valid]")
            assertEquals(listOf("first"), repo.all().map { it.id })
            assertEquals(StorageState.Ready, repo.state.value)
        }
    }

    @Test fun databaseConflictDuringImportLeavesNoPartialRowsAndNoMigrationMarker() = runTest {
        databaseTest { db, repo, dir ->
            val existing = TodoItem(id = "conflict", title = "Existing", createdAt = now)
            db.todos().save(TodoEntity.from(existing))
            val original = """[{"id":"first","title":"A","createdAt":"2026-11-04T08:00:00Z"},{"id":"conflict","title":"B","createdAt":"2026-11-04T08:00:00Z"}]"""
            val file = File(dir, "todos.json").apply { writeText(original) }
            assertNotNull(runCatching { repo.initialize() }.exceptionOrNull())
            assertEquals(listOf("conflict"), db.todos().all().map { it.id })
            assertEquals(0, db.todos().migrated("todos-json-v1"))
            assertEquals(original, file.readText())
        }
    }

    @Test fun onlySuccessfulCurrentDeliveryIsRecordedAndDuplicatePublicationIsPrevented() = runTest {
        databaseTest { _, repo, _ ->
            val item = TodoItem(id = "delivery", title = "Current title", createdAt = now,
                remindAt = now.plusSeconds(60), reminderRevision = 3)
            repo.save(item)
            val at = item.remindAt!!.toEpochMilli()
            var calls = 0
            suspend fun deliver(rev: Long, success: Boolean) = repo.deliverReminder(item.id, rev, at, { now }) {
                calls++; assertEquals("Current title", it.title); success
            }
            assertNull(repo.deliveredAt(item.id, 3))
            assertFalse(deliver(2, true))
            assertEquals(0, calls)
            assertNull(repo.deliveredAt(item.id, 2))
            assertFalse(deliver(3, false))
            assertNull(repo.deliveredAt(item.id, 3))
            assertTrue(deliver(3, true))
            assertEquals(now, repo.deliveredAt(item.id, 3))
            assertFalse(deliver(3, true))
            assertEquals(2, calls)
            val uc = TodoUseCases(repo, RecordedScheduler(), FrozenClock(now))
            assertTrue(uc.wasDelivered(item))
            val updated = uc.updateItem(item.id, TodoEdit("Current title", null, null, now.plusSeconds(120))).item
            assertFalse(uc.wasDelivered(updated))
            assertNull(repo.deliveredAt(item.id, 4))
        }
    }

    @Test fun completedDeletedAndChangedRemindersCannotPublishAndThrownPublicationIsRetryable() = runTest {
        databaseTest { _, repo, _ ->
            val item = TodoItem(id = "rejected", title = "X", createdAt = now, remindAt = now, reminderRevision = 1)
            val at = now.toEpochMilli()
            for (current in listOf(item.copy(completedAt = now), item.copy(reminderRevision = 2),
                item.copy(remindAt = null))) {
                repo.save(current)
                assertFalse(repo.deliverReminder(item.id, 1, at, { now }) { fail("must not publish"); true })
            }
            repo.delete(item.id)
            assertFalse(repo.deliverReminder(item.id, 1, at, { now }) { fail("must not publish"); true })
            repo.save(item)
            assertNotNull(runCatching {
                repo.deliverReminder(item.id, 1, at, { now }) { error("both display paths failed") }
            }.exceptionOrNull())
            assertNull(repo.deliveredAt(item.id, 1))
            assertTrue(repo.deliverReminder(item.id, 1, at, { now }) { true })
        }
    }

    @Test fun concurrentDeliveriesPublishExactlyOnce() = runTest {
        databaseTest { _, repo, _ ->
            repo.save(TodoItem(id = "once", title = "X", createdAt = now, remindAt = now))
            var publications = 0
            val first = async(Dispatchers.IO) {
                repo.deliverReminder("once", 0, now.toEpochMilli(), { now }) { publications++; true }
            }
            val second = async(Dispatchers.IO) {
                repo.deliverReminder("once", 0, now.toEpochMilli(), { now }) { publications++; true }
            }
            assertEquals(1, listOf(first.await(), second.await()).count { it })
            assertEquals(1, publications)
        }
    }
}
