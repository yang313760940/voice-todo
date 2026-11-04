package com.voicetodo.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.domain.FrozenClock
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
        val db = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath).build()
        try {
            val useCases = TodoUseCases(RoomTodoRepository(db,
                FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai"))),
                RecordedScheduler(), FrozenClock(Instant.parse("2026-11-04T11:00:00Z")))
            val item = useCases.findItem("old")
            assertEquals("旧事项", item.title)
            assertEquals(LocalDate.of(2026, 11, 5), item.plannedDate)
            assertEquals(Instant.parse("2026-11-05T12:00:00Z"), item.dueAt)
            assertEquals(Instant.parse("2026-11-04T10:00:00Z"), item.remindAt)
            assertTrue(useCases.wasDelivered(item))
            assertEquals(listOf("old"), useCases.listItems().map { it.id })
        } finally {
            db.close()
        }
    }
}
