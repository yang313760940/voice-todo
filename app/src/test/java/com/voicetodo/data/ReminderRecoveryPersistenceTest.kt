package com.voicetodo.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoUseCases
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReminderRecoveryPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun reopeningPersistentDatabaseRestoresFutureAndReportsPastWithoutRinging() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val databaseFile = temp.newFile("recovery.db")
        val legacy = FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))
        val now = Instant.parse("2026-11-04T00:00:00Z")
        fun open() = Room.databaseBuilder(app, TodoDatabase::class.java, databaseFile.absolutePath).build()

        var db = open()
        val futureId: String
        val pastId = "past-persistent"
        try {
            val repo = RoomTodoRepository(db, legacy)
            val useCases = TodoUseCases(repo, RecordedScheduler(), FrozenClock(now), ZoneId.of("Asia/Shanghai"))
            futureId = useCases.confirmDraft("未来提醒", now.plusSeconds(3600)).item.id
            repo.save(com.voicetodo.domain.TodoItem(
                id = pastId,
                title = "错过提醒",
                remindAt = now.minusSeconds(60),
                createdAt = now.minusSeconds(120),
            ))
        } finally {
            db.close()
        }

        db = open()
        try {
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy), scheduler,
                FrozenClock(now), ZoneId.of("Asia/Shanghai"))

            useCases.reconcileReminders()

            assertTrue(futureId to futureId in scheduler.pending)
            assertEquals(listOf(pastId), useCases.listMissedReminders().map { it.item.id })
            assertTrue("过去提醒不得集中补响", scheduler.pending.keys.none { it.first == pastId })
        } finally {
            db.close()
        }
    }
}
