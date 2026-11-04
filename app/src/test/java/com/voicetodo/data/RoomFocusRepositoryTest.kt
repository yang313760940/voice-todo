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
        fun open(version: Int, withFocus: Boolean) = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath)
            .addMigrations(TodoDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        var db = open(1, false)
        try {
            db.todos().save(TodoEntity("legacy", "Legacy", null, null, null, null, now, 0))
            db.close()
            db = open(1, false)
            val repo = RoomFocusRepository(db)
            assertEquals(FocusSettings(), repo.settings())
            assertEquals("Legacy", db.todos().all().single().title)
        } finally { db.close() }
    }

    @Test fun recoveryMarksEndedSessionOnceAndDoesNotRepeatCountAcrossMidnight() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
            .addMigrations(TodoDatabase.MIGRATION_1_2)
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
