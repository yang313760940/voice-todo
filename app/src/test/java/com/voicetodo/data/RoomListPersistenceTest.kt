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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomListPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var useCases: TodoUseCases
    private val clock = FrozenClock(Instant.parse("2026-11-05T01:00:00Z"))

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(
            db,
            FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")),
        )
        useCases = TodoUseCases(repo, RecordedScheduler(), clock)
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun listAndItemMembershipPersistThroughRoom() = runTest {
        val work = useCases.createList("工作")
        val item = useCases.confirmDraft("写周报", listId = work.id).item

        assertEquals(listOf(work), useCases.listTodoLists())
        assertEquals(work.id, useCases.findItem(item.id).listId)

        val reopened = RoomTodoRepository(
            db,
            FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")),
        )
        assertEquals("工作", reopened.lists().single().name)
        assertEquals(work.id, reopened.findById(item.id)?.listId)
    }

    @Test fun deletingListMovesItemsToInboxInOneTransaction() = runTest {
        val work = useCases.createList("工作")
        val item = useCases.confirmDraft("保留事项", listId = work.id).item

        useCases.deleteList(work.id)

        assertNull(useCases.findItem(item.id).listId)
        assertEquals("保留事项", useCases.findItem(item.id).title)
        assertEquals(0, useCases.listTodoLists().size)
    }
}
