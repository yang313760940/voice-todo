package com.voicetodo.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoUseCases
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomReminderUpgradeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun versionOneItemsAndSuccessfulDeliverySurviveUpgradeWithoutLegacyReimport() = runTest {
        val file = temp.newFile("version-one.db")
        // External compatibility fixture from the checked-in Room version-one schema.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE todos (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, remindAt TEXT, plannedDate TEXT, dueAt TEXT, completedAt TEXT, createdAt TEXT NOT NULL, reminderRevision INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE deliveries (itemId TEXT NOT NULL, reminderRevision INTEGER NOT NULL, deliveredAt TEXT NOT NULL, PRIMARY KEY(itemId, reminderRevision))")
            old.execSQL("CREATE TABLE storage_migrations (name TEXT NOT NULL PRIMARY KEY)")
            old.execSQL("INSERT INTO todos VALUES ('old', '旧事项', '2026-11-04T10:00:00.000000000Z', '2026-11-05', '2026-11-05T12:00:00.000000000Z', NULL, '2026-11-04T08:00:00.000000000Z', 7)")
            old.execSQL("INSERT INTO deliveries VALUES ('old', 7, '2026-11-04T10:00:01.000000000Z')")
            old.execSQL("INSERT INTO storage_migrations VALUES ('todos-json-v1')")
            old.version = 1
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath)
            .addMigrations(TodoDatabase.MIGRATION_3_4, TodoDatabase.MIGRATION_5_6)
            .build()
        try {
            val useCases = TodoUseCases(RoomTodoRepository(db,
                FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))),
                RecordedScheduler(), FrozenClock(Instant.parse("2026-11-04T11:00:00Z")))
            val item = useCases.findItem("old")
            assertEquals("旧事项", item.title)
            assertEquals(LocalDate.of(2026, 11, 5), item.plannedDate)
            assertEquals(Instant.parse("2026-11-05T12:00:00Z"), item.dueAt)
            assertEquals(Instant.parse("2026-11-04T10:00:00Z"), item.remindAt)
            assertEquals(Quadrant.NotImportantNotUrgent, item.quadrant)
            assertTrue(useCases.wasDelivered(item))
            assertEquals(listOf("old"), useCases.listItems().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test fun versionTwoRemindersSurviveQuadrantMigrationWithSafeDefault() = runTest {
        val file = temp.newFile("version-two.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE todos (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                    "remindAt TEXT, plannedDate TEXT, dueAt TEXT, completedAt TEXT, " +
                    "createdAt TEXT NOT NULL, reminderRevision INTEGER NOT NULL, " +
                    "reminders TEXT NOT NULL DEFAULT '[]')",
            )
            old.execSQL(
                "CREATE TABLE deliveries (itemId TEXT NOT NULL, reminderRevision INTEGER NOT NULL, " +
                    "deliveredAt TEXT NOT NULL, PRIMARY KEY(itemId, reminderRevision))",
            )
            old.execSQL(
                "CREATE TABLE reminder_deliveries (itemId TEXT NOT NULL, reminderId TEXT NOT NULL, " +
                    "revision INTEGER NOT NULL, deliveredAt TEXT NOT NULL, " +
                    "PRIMARY KEY(itemId, reminderId, revision), " +
                    "FOREIGN KEY(itemId) REFERENCES todos(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            old.execSQL(
                "CREATE INDEX index_reminder_deliveries_itemId ON reminder_deliveries(itemId)",
            )
            old.execSQL("CREATE TABLE storage_migrations (name TEXT NOT NULL PRIMARY KEY)")
            old.execSQL(
                "INSERT INTO todos VALUES ('v2', '升级前事项', NULL, NULL, NULL, NULL, " +
                    "'2026-11-04T08:00:00.000000000Z', 3, " +
                    "'[{\"id\":\"r1\",\"at\":\"2026-11-05T10:00:00Z\",\"revision\":3," +
                    "\"outcome\":\"Scheduled\"}]')",
            )
            old.version = 2
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath)
            .addMigrations(TodoDatabase.MIGRATION_3_4, TodoDatabase.MIGRATION_5_6)
            .build()
        try {
            val item = TodoUseCases(
                RoomTodoRepository(
                    db,
                    FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")),
                ),
                RecordedScheduler(),
                FrozenClock(Instant.parse("2026-11-04T11:00:00Z")),
            ).findItem("v2")

            assertEquals("升级前事项", item.title)
            assertEquals(1, item.reminders.size)
            assertEquals(Instant.parse("2026-11-05T10:00:00Z"), item.reminders.single().at)
            assertEquals(Quadrant.NotImportantNotUrgent, item.quadrant)
        } finally {
            db.close()
        }
    }

    @Test fun versionFiveItemsAndDeliveriesSurviveListMigration() = runTest {
        val file = temp.newFile("version-five.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE todos (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                    "remindAt TEXT, plannedDate TEXT, dueAt TEXT, completedAt TEXT, " +
                    "createdAt TEXT NOT NULL, reminderRevision INTEGER NOT NULL, " +
                    "reminders TEXT NOT NULL DEFAULT '[]', " +
                    "quadrant TEXT NOT NULL DEFAULT 'NotImportantNotUrgent', " +
                    "suggestedQuadrant TEXT)",
            )
            old.execSQL(
                "CREATE TABLE deliveries (itemId TEXT NOT NULL, reminderRevision INTEGER NOT NULL, " +
                    "deliveredAt TEXT NOT NULL, PRIMARY KEY(itemId, reminderRevision))",
            )
            old.execSQL(
                "CREATE TABLE reminder_deliveries (itemId TEXT NOT NULL, reminderId TEXT NOT NULL, " +
                    "revision INTEGER NOT NULL, deliveredAt TEXT NOT NULL, " +
                    "PRIMARY KEY(itemId, reminderId, revision), " +
                    "FOREIGN KEY(itemId) REFERENCES todos(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            old.execSQL("CREATE INDEX index_reminder_deliveries_itemId ON reminder_deliveries(itemId)")
            old.execSQL("CREATE TABLE storage_migrations (name TEXT NOT NULL PRIMARY KEY)")
            old.execSQL(
                "CREATE TABLE focus_sessions (id TEXT NOT NULL PRIMARY KEY, phase TEXT NOT NULL, " +
                    "startedAt TEXT NOT NULL, endsAt TEXT NOT NULL, lastResumedAt TEXT NOT NULL, " +
                    "durationSeconds INTEGER NOT NULL, pausedAt TEXT, accumulatedFocusSeconds INTEGER NOT NULL, " +
                    "completedPomodoros INTEGER NOT NULL, itemId TEXT, state TEXT NOT NULL, endedAt TEXT)",
            )
            old.execSQL(
                "CREATE TABLE focus_settings (id INTEGER NOT NULL PRIMARY KEY, focusMinutes INTEGER NOT NULL, " +
                    "shortBreakMinutes INTEGER NOT NULL, longBreakMinutes INTEGER NOT NULL, longBreakEvery INTEGER NOT NULL)",
            )
            old.execSQL("INSERT INTO focus_settings VALUES (1, 25, 5, 15, 4)")
            old.execSQL(
                "INSERT INTO todos VALUES ('v5', '升级前清单前事项', '2026-11-05T10:00:00.000000000Z', " +
                    "'2026-11-05', NULL, NULL, '2026-11-04T08:00:00.000000000Z', 3, " +
                    "'[{\"id\":\"r1\",\"at\":\"2026-11-05T10:00:00Z\",\"revision\":3," +
                    "\"outcome\":\"Scheduled\"}]', 'ImportantUrgent', 'ImportantUrgent')",
            )
            old.execSQL(
                "INSERT INTO reminder_deliveries VALUES ('v5', 'r1', 3, '2026-11-05T10:00:01.000000000Z')",
            )
            old.version = 5
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath)
            .addMigrations(TodoDatabase.MIGRATION_3_4, TodoDatabase.MIGRATION_5_6)
            .build()
        try {
            val useCases = TodoUseCases(
                RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))),
                RecordedScheduler(),
                FrozenClock(Instant.parse("2026-11-04T11:00:00Z")),
            )
            val item = useCases.findItem("v5")
            assertNull(item.listId)
            assertEquals(Quadrant.ImportantUrgent, item.quadrant)
            assertEquals(Quadrant.ImportantUrgent, item.suggestedQuadrant)
            assertEquals(1, item.reminders.size)
            assertTrue(useCases.wasDelivered(item))
            assertTrue(useCases.listTodoLists().isEmpty())
        } finally {
            db.close()
        }
    }
}
