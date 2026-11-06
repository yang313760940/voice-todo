package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoUseCases
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h1000dp")
class TodoHomeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var useCases: TodoUseCases
    private lateinit var vm: TodoViewModel
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-05T01:00:00Z"))
    private val scheduler = RecordedScheduler()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 5)

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), zone))
        useCases = TodoUseCases(repo, scheduler, clock)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(
                    app = app,
                    useCases = useCases,
                    storage = repo,
                    capabilities = { ReminderCapabilities(true, true, true) },
                    clock = clock,
                    zoneId = zone,
                ) as T
        })[TodoViewModel::class.java]
        compose.setContent { MaterialTheme { TodoScreen(vm) } }
        waitFor { !vm.state.value.loading }
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        db.close()
    }

    @Test fun todayIsDefaultDeduplicatesSourcesAndSwitchesTheSameData() {
        val duplicate = TodoItem(
            id = "duplicate",
            title = "重复来源",
            plannedDate = today,
            dueAt = Instant.parse("2026-11-05T12:00:00Z"),
            remindAt = Instant.parse("2026-11-05T03:00:00Z"),
            createdAt = clock.now(),
        )
        val overdue = TodoItem(
            id = "overdue",
            title = "逾期事项",
            dueAt = clock.now().minusSeconds(3600),
            createdAt = clock.now(),
        )
        val future = TodoItem(
            id = "future",
            title = "后天事项",
            plannedDate = today.plusDays(2),
            createdAt = clock.now(),
        )
        val completed = TodoItem(
            id = "completed",
            title = "完成事项",
            plannedDate = today,
            completedAt = clock.now(),
            createdAt = clock.now(),
        )
        runBlocking { listOf(duplicate, overdue, future, completed).forEach { repo.save(it) } }
        compose.runOnIdle { vm.onRefresh() }
        waitFor { vm.state.value.items.size == 3 }

        compose.onNodeWithTag("view_title").assertTextEquals("今天")
        compose.onAllNodesWithText("重复来源").assertCountEquals(1)
        compose.onNodeWithText("逾期事项").assertExists()
        compose.onNodeWithText("后天事项").assertDoesNotExist()
        compose.onNodeWithText("完成事项").assertDoesNotExist()

        selectView("view_all")
        compose.onNodeWithText("后天事项").assertExists()

        selectView("view_recent7")
        compose.onNodeWithText("后天事项").assertExists()

        selectView("view_completed")
        compose.onNodeWithText("完成事项").assertExists()
    }

    @Test fun inboxKeepsScheduledItemsAndFiltersOnlyUnscheduledOnes() {
        val scheduled = TodoItem(
            id = "scheduled",
            title = "已安排但未分类",
            plannedDate = today,
            createdAt = clock.now(),
        )
        val unscheduled = TodoItem(
            id = "unscheduled",
            title = "完全未安排",
            createdAt = clock.now(),
        )
        val work = runBlocking { useCases.createList("工作") }
        val listed = TodoItem(
            id = "listed",
            title = "工作清单事项",
            plannedDate = today,
            listId = work.id,
            createdAt = clock.now(),
        )
        runBlocking { listOf(scheduled, unscheduled, listed).forEach { repo.save(it) } }
        compose.runOnIdle { vm.onRefresh() }
        waitFor { vm.state.value.items.size == 3 }

        selectView("view_inbox")
        compose.onNodeWithText("已安排但未分类").assertExists()
        compose.onNodeWithText("完全未安排").assertExists()
        compose.onNodeWithText("工作清单事项").assertDoesNotExist()

        compose.onNodeWithTag("unscheduled_filter").performClick()
        compose.onNodeWithText("已安排但未分类").assertDoesNotExist()
        compose.onNodeWithText("完全未安排").assertExists()
    }

    @Test fun addEntriesTargetTheSelectedListAndCancelCreatesNothing() {
        compose.onNodeWithTag("view_selector").performClick()
        compose.onNodeWithTag("view_create_list").performClick()
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("new_list_name"))
        compose.onNodeWithTag("new_list_name").performTextInput("工作")
        compose.onNodeWithTag("create_list_confirm").performClick()
        waitFor { vm.state.value.todoLists.size == 1 && !vm.state.value.busy }
        val work = vm.state.value.todoLists.single()
        assertEquals("工作", work.name)
        selectView("view_list_${work.id}")

        compose.onNodeWithTag("voice_mic_button").performTouchInput { longClick() }
        waitFor { vm.state.value.detail?.original == null && vm.state.value.detail != null }
        compose.onNodeWithTag("detail_list_selector").assertTextContains("工作", substring = true)
        compose.onNodeWithTag("detail_note_field").assertExists()
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag("detail_plannedDate_field"))
        compose.onNodeWithTag("detail_plannedDate_field").assertExists()
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag("repeat_none"))
        compose.onNodeWithTag("repeat_none").assertExists()
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag("detail_title_field"))
        compose.onNodeWithTag("detail_title_field").performTextReplacement("清单内文字添加")
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag("detail_save_button"))
        compose.onNodeWithTag("detail_save_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }
        assertEquals(work.id, vm.state.value.items.single().listId)
        compose.onNodeWithText("清单内文字添加").assertExists()

        compose.onNodeWithTag("voice_mic_button").performClick()
        waitFor { vm.state.value.addOpen }
        compose.onNodeWithTag("voice_input_error").assertExists()
        compose.onNodeWithTag("voice_close_button").performClick()
        waitFor { !vm.state.value.addOpen }
        assertEquals(1, vm.state.value.items.size)
    }

    @Test fun detailListAndDateEditsAreVisibleInEveryAffectedView() {
        val work = runBlocking { useCases.createList("工作") }
        val life = runBlocking { useCases.createList("生活") }
        val item = TodoItem(
            id = "move",
            title = "跨视图同步",
            plannedDate = today,
            listId = work.id,
            createdAt = clock.now(),
        )
        runBlocking { repo.save(item) }
        compose.runOnIdle { vm.onRefresh() }
        waitFor { vm.state.value.todoLists.size == 2 && vm.state.value.items.any { it.id == item.id } }
        assertEquals(1, runBlocking { repo.all().size })

        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("item_row_0"))
        compose.onNodeWithText("跨视图同步").assertExists()
        compose.onNodeWithTag("open_${item.id}").performClick()
        waitFor { vm.state.value.detail?.original?.id == item.id }
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag("detail_list_selector"))
        compose.onNodeWithTag("detail_list_selector").performClick()
        compose.onNodeWithTag("detail_list_${life.id}").performClick()
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag("detail_plannedDate_field"))
        compose.onNodeWithTag("detail_plannedDate_field").performTextReplacement(today.plusDays(1).toString())
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag("detail_save_button"))
        compose.onNodeWithTag("detail_save_button").performClick()
        waitFor { vm.state.value.detail == null && !vm.state.value.busy }

        val saved = vm.state.value.items.single { it.id == item.id }
        assertEquals(life.id, saved.listId)
        assertEquals(today.plusDays(1), saved.plannedDate)

        compose.onNodeWithText("今天没有要处理的事项").assertExists()
        selectView("view_recent7")
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("item_row_0"))
        compose.onNodeWithText("跨视图同步").assertExists()
        selectView("view_list_${life.id}")
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("item_row_0"))
        compose.onNodeWithText("跨视图同步").assertExists()
        selectView("view_list_${work.id}")
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("empty_view"))
        compose.onNodeWithText("这个清单还没有事项").assertExists()
    }

    private fun selectView(tag: String) {
        compose.onNodeWithTag("view_selector").performClick()
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

}
