package com.voicetodo.ui

import android.app.Application
import android.os.Looper
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.voicetodo.settings.QuadrantPreferencesStore
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
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
class QuadrantScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var useCases: TodoUseCases
    private lateinit var vm: TodoViewModel
    private lateinit var preferencesStore: QuadrantPreferencesStore
    private var visibleVm by mutableStateOf<TodoViewModel?>(null)
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-05T01:00:00Z"))
    private val scheduler = RecordedScheduler()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")))
        useCases = TodoUseCases(repo, scheduler, clock)
        val preferences = app.getSharedPreferences("quadrant-test-${UUID.randomUUID()}", 0)
        preferencesStore = QuadrantPreferencesStore(preferences)
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
        note: String = "",
        completedAt: Instant? = null,
    ): TodoItem {
        val item = TodoItem(
            id = UUID.randomUUID().toString(),
            title = title,
            note = note,
            dueAt = dueAt,
            createdAt = createdAt,
            completedAt = completedAt,
            quadrant = quadrant,
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
                                    quadrantContent = { QuadrantScreen(current, preferencesStore) },
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
    fun clickingAnItemOpensExistingDetailWithTitleAndDescription() {
        val item = seed("查看详情", Quadrant.ImportantUrgent, note = "准备材料")
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_item_${item.id}").performClick()
        waitFor { vm.state.value.detail?.original?.id == item.id }

        compose.onNodeWithTag("detail_title_field").assertTextEquals("标题", "查看详情")
        compose.onNodeWithTag("detail_note_field").assertTextEquals(
            "描述（可选，不进入提醒正文）",
            "准备材料",
        )
    }

    @Test
    fun longPressDragMovesAcrossFixedQuadrants() {
        val item = seed("拖拽事项", Quadrant.NotImportantNotUrgent)
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        val start = compose.onNodeWithTag("quadrant_item_${item.id}").fetchSemanticsNode().boundsInRoot.center
        val target = compose.onNodeWithTag("quadrant_ImportantUrgent").fetchSemanticsNode().boundsInRoot.center

        val screenOrigin = compose.onNodeWithTag("quadrant_screen")
            .fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("quadrant_screen").performTouchInput {
            down(start - screenOrigin)
            advanceEventTime(1_000)
            moveBy(Offset(8f, -8f))
            advanceEventTime(50)
            moveTo(target - screenOrigin)
            advanceEventTime(50)
            up()
        }

        waitFor { vm.state.value.items.singleOrNull()?.quadrant == Quadrant.ImportantUrgent && !vm.state.value.busy }
        assertEquals(Quadrant.ImportantUrgent, runBlocking { repo.findById(item.id) }!!.quadrant)
    }

    @Test
    fun allQuadrantsKeepEqualFixedHeightsWhenOneListIsLong() {
        repeat(12) { seed("重要事项$it", Quadrant.ImportantUrgent) }
        val other = seed("独立列表", Quadrant.ImportantNotUrgent)
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        val topLeft = compose.onNodeWithTag("quadrant_ImportantUrgent").fetchSemanticsNode().boundsInRoot
        val topRight = compose.onNodeWithTag("quadrant_ImportantNotUrgent").fetchSemanticsNode().boundsInRoot
        val bottomLeft = compose.onNodeWithTag("quadrant_NotImportantUrgent").fetchSemanticsNode().boundsInRoot

        assertTrue("同一行高度应一致", abs(topLeft.height - topRight.height) < 1f)
        assertTrue("上下两行高度应一致", abs(topLeft.height - bottomLeft.height) < 1f)
        val first = vm.state.value.items.first { it.title == "重要事项0" }
        compose.onNodeWithTag("quadrant_list_ImportantUrgent")
            .performScrollToNode(hasTestTag("quadrant_item_${first.id}"))
        compose.onNodeWithTag("quadrant_item_${other.id}").assertIsDisplayed()
    }

    @Test
    fun completedItemsAppearAtTheBottomAndCanBeRestored() {
        val pending = seed("未完成", Quadrant.ImportantUrgent)
        val completed = seed(
            "已完成",
            Quadrant.ImportantUrgent,
            completedAt = clock.now().minus(Duration.ofMinutes(5)),
        )
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_item_${completed.id}").assertDoesNotExist()
        compose.onNodeWithTag("quadrant_more").performClick()
        compose.onNodeWithTag("quadrant_toggle_completed").performClick()
        compose.onNodeWithTag("quadrant_item_${completed.id}").assertIsDisplayed()

        val pendingTop = compose.onNodeWithTag("quadrant_item_${pending.id}").fetchSemanticsNode().boundsInRoot.top
        val completedTop = compose.onNodeWithTag("quadrant_item_${completed.id}").fetchSemanticsNode().boundsInRoot.top
        assertTrue("已完成事项应排在未完成事项之后", pendingTop < completedTop)
        compose.onNodeWithTag("quadrant_complete_${completed.id}").performClick()
        waitFor { vm.state.value.items.any { it.id == completed.id } }
        assertEquals(null, runBlocking { repo.findById(completed.id) }!!.completedAt)
    }

    @Test
    fun layoutEditSwapsQuadrantsAndPersistsTheOrder() {
        showApp()

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_more").performClick()
        compose.onNodeWithTag("quadrant_edit_layout").performClick()
        val start = compose.onNodeWithTag("quadrant_ImportantUrgent").fetchSemanticsNode().boundsInRoot.center
        val target = compose.onNodeWithTag("quadrant_NotImportantNotUrgent").fetchSemanticsNode().boundsInRoot.center

        val screenOrigin = compose.onNodeWithTag("quadrant_screen")
            .fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("quadrant_screen").performTouchInput {
            down(start - screenOrigin)
            advanceEventTime(1_000)
            moveBy(Offset(8f, 8f))
            advanceEventTime(50)
            moveTo(target - screenOrigin)
            advanceEventTime(50)
            up()
        }

        compose.waitUntil(5_000) {
            preferencesStore.load().order.first() == Quadrant.NotImportantNotUrgent
        }
        assertEquals(Quadrant.ImportantUrgent, preferencesStore.load().order.last())
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
    }

    @Test
    fun darkThemeLargeFontAndLandscapeStillExposeTheGuide() {
        showApp(dark = true, fontScale = 1.5f, wideLandscape = true)

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_screen").assertIsDisplayed()
        compose.onNodeWithTag("quadrant_more").performClick()
        compose.onNodeWithTag("quadrant_open_guide").performClick()
        compose.onNodeWithTag("quadrant_guide").assertIsDisplayed()
    }

    private fun waitFor(condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (error: Exception) {
            throw AssertionError("UI state at timeout: ${vm.state.value}", error)
        }
    }
}
