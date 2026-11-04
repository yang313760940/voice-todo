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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomFocusRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-05T01:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun activeSessionAndSettingsSurviveDatabaseReopening() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = temp.newFile("focus.db")
        fun open() = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath).build()
        var db = open()
        try {
            var repo = RoomFocusRepository(db)
            repo.saveSettings(FocusSettings(30, 6, 18, 3))
            val session = FocusSession(
                id = "active", phase = FocusPhase.Focus, startedAt = now,
                endsAt = now.plusSeconds(1800), lastResumedAt = now, durationSeconds = 1800,
                state = FocusSessionState.Paused, accumulatedFocusSeconds = 600,
            )
            repo.save(session)
            db.close()

            db = open()
            repo = RoomFocusRepository(db)
            assertEquals(FocusSettings(30, 6, 18, 3), repo.settings())
            assertEquals(session, repo.activeSession())
        } finally { db.close() }
    }

    @Test fun migrationKeepsTodosAndAddsFocusTables() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = temp.newFile("legacy.db")
        // Version 3 is the pre-Pomodoro schema released on main.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE todos (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                    "remindAt TEXT, plannedDate TEXT, dueAt TEXT, completedAt TEXT, " +
                    "createdAt TEXT NOT NULL, reminderRevision INTEGER NOT NULL, " +
                    "reminders TEXT NOT NULL DEFAULT '[]', " +
                    "quadrant TEXT NOT NULL DEFAULT 'NotImportantNotUrgent')",
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
                "INSERT INTO todos VALUES ('legacy', 'Legacy', NULL, NULL, NULL, NULL, " +
                    "'2026-11-05T01:00:00.000000000Z', 0, '[]', 'NotImportantNotUrgent')",
            )
            old.version = 3
        }
        val db = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath)
            .addMigrations(TodoDatabase.MIGRATION_3_4, TodoDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = RoomFocusRepository(db)
            assertEquals(FocusSettings(), repo.settings())
            assertEquals("Legacy", db.todos().all().single().title)
        } finally { db.close() }
    }

    @Test fun recoveryMarksEndedSessionOnceAndDoesNotRepeatCountAcrossMidnight() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
            .build()
        try {
            val repo = RoomFocusRepository(db)
            val scheduler = RecordedFocusScheduler()
            val clock = MutableClock(now, zone)
            val useCases = FocusUseCases(repo, scheduler, clock, zone = zone)
            useCases.startFocus()
            clock.now = now.plusSeconds(1500)
            useCases.reconcileEnd()
            assertEquals(FocusSessionState.EndedPendingConfirmation, repo.activeSession()!!.state)
            useCases.reconcileEnd()
            useCases.confirmEnded()
            assertEquals(1, useCases.snapshot().todayCount)

            clock.now = Instant.parse("2026-11-06T01:00:00Z")
            assertEquals(0, useCases.snapshot().todayCount)
            assertEquals(now.atZone(zone).toLocalDate() to 1, useCases.snapshot().lastSevenDaysCounts.first { it.first == now.atZone(zone).toLocalDate() })
        } finally { db.close() }
    }
}
