package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.voicetodo.domain.TodoUseCases
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h1000dp")
class TodoInputFlowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var vm: TodoViewModel
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-04T09:00:00Z"))

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")))
        val useCases = TodoUseCases(repo, RecordedScheduler(), clock)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(
                    app = app,
                    useCases = useCases,
                    storage = repo,
                    capabilities = { ReminderCapabilities(true, true, true) },
                    clock = clock,
                    zoneId = ZoneId.of("Asia/Shanghai"),
                ) as T
        })[TodoViewModel::class.java]
        compose.setContent { MaterialTheme { TodoScreen(vm) } }
        waitFor { !vm.state.value.loading }
        compose.runOnIdle { vm.onOpenAdd() }
        waitFor { vm.state.value.addOpen }
    }

    @After fun teardown() {
        compose.runOnIdle { store.clear() }
        db.close()
    }

    @Test fun voiceEntryExplainsServiceIsNotConfiguredAndKeepsTextUsable() {
        compose.onNodeWithTag("voice_unavailable_notice")
            .assertTextContains("语音识别未配置", substring = true)
        compose.onNodeWithTag("record_permission_status")
            .assertTextContains("麦克风 ✓", substring = true)
        compose.onNodeWithTag("title_field").assertIsEnabled().performTextInput("纯文字事项")
        compose.onNodeWithTag("manual_create_button").assertIsEnabled().performClick()
        compose.onNodeWithTag("detail_screen").assertIsDisplayed()
    }

    @Test fun voiceAddEntryOpensPanelWithNoticeAndCreatesNothing() {
        compose.runOnIdle { vm.onCloseAdd() }
        waitFor { !vm.state.value.addOpen }

        compose.onNodeWithTag("voice_add_button").performClick()
        waitFor { vm.state.value.addOpen }

        compose.onNodeWithTag("voice_unavailable_notice")
            .assertTextContains("语音识别未配置", substring = true)
        assertTrue(
            vm.state.value.inputError.orEmpty().contains("语音识别未配置"),
        )
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun manualCreationCarriesEditableTextThroughExistingDetailAndPersistsFields() {
        compose.onNodeWithTag("title_field").performTextInput("识别后可修改")
        compose.onNodeWithTag("manual_create_button").performClick()
        compose.onNodeWithTag("detail_title_field").performTextReplacement("修改后的事项")
        compose.onNodeWithTag("detail_plannedDate_field").performTextInput("2026-11-05")
        compose.onNodeWithTag("detail_dueAt_field").performTextInput("2026-11-06 20:30")
        compose.onNodeWithTag("detail_screen").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("detail_save_button"),
        )
        compose.onNodeWithTag("detail_save_button").performClick()
        waitFor { !vm.state.value.busy && vm.state.value.items.size == 1 }

        val saved = vm.state.value.items.single()
        assertEquals("修改后的事项", saved.title)
        assertEquals(LocalDate.of(2026, 11, 5), saved.plannedDate)
        assertEquals(Instant.parse("2026-11-06T12:30:00Z"), saved.dueAt)
        assertTrue(vm.state.value.draftText.isEmpty())
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

}
