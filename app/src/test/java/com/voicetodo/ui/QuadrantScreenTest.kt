package com.voicetodo.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoUseCases
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
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
import android.os.Looper
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h1000dp")
class QuadrantScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var useCases: TodoUseCases
    private lateinit var vm: TodoViewModel
    private var visibleVm by mutableStateOf<TodoViewModel?>(null)
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-05T01:00:00Z"))
    private val scheduler = RecordedScheduler()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")))
        useCases = TodoUseCases(repo, scheduler, clock)
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        db.close()
    }

    private fun makeViewModel(): TodoViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(
                    app, useCases, repo, { ReminderCapabilities(true, true, true) },
                    clock, ZoneId.of("Asia/Shanghai"),
                ) as T
        })[TodoViewModel::class.java]
    }

    private fun seed(
        title: String,
        quadrant: Quadrant,
        dueAt: Instant? = null,
        createdAt: Instant = clock.now(),
        suggestedQuadrant: Quadrant? = null,
    ): TodoItem {
        val item = TodoItem(
            id = UUID.randomUUID().toString(),
            title = title,
            dueAt = dueAt,
            createdAt = createdAt,
            quadrant = quadrant,
            suggestedQuadrant = suggestedQuadrant,
        )
        runBlocking { repo.save(item) }
        return item
    }

    private fun showApp(
        dark: Boolean = false,
        fontScale: Float = 1f,
        wideLandscape: Boolean = false,
    ) {
        compose.runOnIdle {
            vm = makeViewModel()
            visibleVm = vm
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    val content: @Composable () -> Unit = {
                        visibleVm?.let { current ->
                            key(current) {
                                VoiceTodoNavigationHost(
                                    todoContent = { TodoScreen(current) },
                                    quadrantContent = { QuadrantScreen(current) },
                                    pomodoroContent = { Text("番茄") },
                                    settingsContent = { Text("我的") },
                                )
                            }
                        }
                    }
                    if (wideLandscape) {
                        Box(Modifier.width(720.dp).height(400.dp)) { content() }
                    } else {
                        content()
                    }
                }
            }
        }
        waitFor { !vm.state.value.loading }
    }

    @Test
    fun menuMovePersistsAndSyncsWithTheTodoPage() {
        val item = seed(
            "菜单事项",
            Quadrant.NotImportantNotUrgent,
            dueAt = clock.now().plus(Duration.ofHours(2)),
            suggestedQuadrant = Quadrant.ImportantUrgent,
        )
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_item_${item.id}").assertExists()
        compose.onNodeWithTag("quadrant_value_${item.id}").assertTextEquals("当前：不重要不紧急")
        compose.onNodeWithTag("quadrant_suggestion_${item.id}")
            .assertTextEquals("AI 建议：重要且紧急（仅建议）")

        compose.onNodeWithTag("move_menu_${item.id}").performScrollTo().performClick()
        compose.onNodeWithTag("move_to_ImportantUrgent_${item.id}").performClick()
        waitFor { vm.state.value.items.singleOrNull()?.quadrant == Quadrant.ImportantUrgent && !vm.state.value.busy }

        assertEquals(
            Quadrant.ImportantUrgent,
            runBlocking { repo.findById(item.id) }!!.quadrant,
        )
        compose.onNodeWithTag("quadrant_value_${item.id}").assertTextEquals("当前：重要且紧急")
        compose.onNodeWithTag("quadrant_suggestion_${item.id}")
            .assertTextEquals("AI 建议：重要且紧急（仅建议）")

        compose.onNodeWithTag("nav_todo").performClick()
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("item_row_0"))
        compose.onNodeWithTag("todo_quadrant_${item.id}", useUnmergedTree = true)
            .assertTextEquals("象限：重要且紧急")
    }

    @Test
    fun longPressDragMovesAcrossQuadrants() {
        val item = seed("拖拽事项", Quadrant.NotImportantNotUrgent)
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        val start = compose.onNodeWithTag("quadrant_item_${item.id}").fetchSemanticsNode().boundsInRoot.center
        val target = compose.onNodeWithTag("quadrant_ImportantUrgent").fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput {
            down(start)
            advanceEventTime(1_000)
            moveBy(Offset(8f, -8f))
            advanceEventTime(50)
            moveTo(target)
            advanceEventTime(50)
            up()
        }

        waitFor { vm.state.value.items.singleOrNull()?.quadrant == Quadrant.ImportantUrgent && !vm.state.value.busy }
        assertEquals(Quadrant.ImportantUrgent, runBlocking { repo.findById(item.id) }!!.quadrant)
    }

    @Test
    fun overdueAndNearItemsSortFirstWithinTheirQuadrant() {
        val overdue = seed(
            "逾期事项",
            Quadrant.ImportantUrgent,
            dueAt = clock.now().minus(Duration.ofHours(1)),
        )
        val near = seed(
            "临近事项",
            Quadrant.ImportantUrgent,
            dueAt = clock.now().plus(Duration.ofHours(1)),
        )
        val normal = seed(
            "普通事项",
            Quadrant.ImportantUrgent,
            dueAt = clock.now().plus(Duration.ofDays(3)),
        )
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        val tops = listOf(overdue, near, normal).map {
            compose.onNodeWithTag("quadrant_item_${it.id}").fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("逾期应排在临近之前", tops[0] < tops[1])
        assertTrue("临近应排在普通之前", tops[1] < tops[2])
        compose.onNodeWithTag("quadrant_overdue_${overdue.id}").assertExists()
        compose.onNodeWithTag("quadrant_near_due_${near.id}").assertExists()
    }

    @Test
    fun nearDeadlineDoesNotAutoMigrateQuadrant() {
        val item = seed(
            "即将到期",
            Quadrant.NotImportantNotUrgent,
            dueAt = clock.now().plus(Duration.ofMinutes(30)),
        )
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_near_due_${item.id}").assertExists()
        compose.onNodeWithTag("quadrant_value_${item.id}").assertTextEquals("当前：不重要不紧急")
        assertEquals(Quadrant.NotImportantNotUrgent, runBlocking { repo.findById(item.id) }!!.quadrant)
    }

    @Test
    fun darkThemeLargeFontAndLandscapeStillExposeTheMenuMove() {
        val item = seed("无障碍事项", Quadrant.NotImportantNotUrgent)
        showApp(dark = true, fontScale = 1.5f, wideLandscape = true)

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_screen").assertExists()
        compose.onNodeWithTag("move_menu_${item.id}").performScrollTo().performClick()
        compose.onNodeWithTag("move_to_NotImportantUrgent_${item.id}").performClick()

        waitFor { vm.state.value.items.singleOrNull()?.quadrant == Quadrant.NotImportantUrgent && !vm.state.value.busy }
        assertEquals(Quadrant.NotImportantUrgent, runBlocking { repo.findById(item.id) }!!.quadrant)
    }

    private fun waitFor(condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Exception) {
            throw AssertionError("UI state at timeout: ${vm.state.value}", e)
        }
    }
}
