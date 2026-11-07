package com.voicetodo.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomFocusRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.domain.*
import java.time.Instant
import java.time.ZoneId
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h1000dp")
class PomodoroScreenTest {
    @get:Rule val compose = createComposeRule()
    private val clock = MutableClock(Instant.parse("2026-11-05T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val scheduler = RecordedFocusScheduler()
    private lateinit var db: TodoDatabase
    private lateinit var vm: PomodoroViewModel

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        val todos = RoomTodoRepository(db, FileTodoRepository(app.filesDir, ZoneId.of("Asia/Shanghai")))
        val focus = RoomFocusRepository(db)
        val useCases = FocusUseCases(focus, scheduler, clock, todos, ZoneId.of("Asia/Shanghai"))
        compose.runOnIdle { vm = PomodoroViewModel(app, useCases, todos, clock) }
        compose.setContent { MaterialTheme { PomodoroScreen(vm) } }
        waitFor { vm.state.value.snapshot != null }
    }

    @After fun teardown() { db.close() }

    @Test fun startsPausesResumesAndEndsEarly() {
        compose.onNodeWithTag("item_picker").assertDoesNotExist()
        compose.onNodeWithTag("start_button").performClick()
        waitFor { vm.state.value.snapshot?.session != null }
        Assert.assertNull(vm.state.value.snapshot?.session?.itemId)

        compose.onNodeWithTag("pause_button").performClick()
        waitFor { vm.state.value.snapshot?.session?.state == FocusSessionState.Paused }
        clock.now = clock.now.plusSeconds(600)
        compose.onNodeWithTag("resume_button").performClick()
        waitFor { vm.state.value.snapshot?.session?.state == FocusSessionState.Running }

        clock.now = clock.now.plusSeconds(300)
        compose.onNodeWithTag("end_early_button").performClick()
        waitFor { vm.state.value.snapshot?.session == null }
        Assert.assertEquals(0, vm.state.value.snapshot?.todayCount)
        Assert.assertEquals(300L, vm.state.value.snapshot?.todaySeconds)
    }

    @Test fun settingsCanAdjustDefaultDurations() {
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("menu_focus_settings").performClick()
        compose.onNodeWithTag("setting_专注分钟").performTextReplacement("30")
        waitFor { vm.state.value.settingsDraft?.focusMinutes == 30 }
        compose.onNodeWithTag("save_settings_button").performClick()
        waitFor { vm.state.value.settingsDraft == null && !vm.state.value.busy }
        compose.onNodeWithTag("start_button").performClick()
        waitFor { vm.state.value.snapshot?.session != null }
        Assert.assertEquals(1800L, vm.state.value.snapshot?.session?.durationSeconds)
    }

    @Test fun endedSessionWaitsForConfirmationAndManualBreakDoesNotAutoStart() {
        compose.onNodeWithTag("start_button").performClick()
        waitFor { vm.state.value.snapshot?.session != null }
        val focusSessionId = vm.state.value.snapshot?.session?.id
        Assert.assertNotNull(focusSessionId)
        clock.now = clock.now.plusSeconds(1500)
        compose.runOnIdle { vm.onStartAfterTimeout() }
        waitFor { vm.state.value.snapshot?.session?.state == FocusSessionState.EndedPendingConfirmation }
        compose.onNodeWithTag("confirm_ended_button").performClick()
        waitFor { vm.state.value.snapshot?.session == null }
        Assert.assertEquals(1, vm.state.value.snapshot?.todayCount)
        compose.onNodeWithTag("phase_selector").performClick()
        compose.onNodeWithTag("phase_ShortBreak").performClick()
        compose.onNodeWithTag("start_button").performClick()
        waitFor { vm.state.value.snapshot?.session?.phase == FocusPhase.ShortBreak }
    }

    @Test fun stopwatchModeStartsFromSinglePrimaryButton() {
        compose.onNodeWithTag("mode_stopwatch").performClick()
        compose.onNodeWithTag("start_stopwatch_button").performClick()
        waitFor { vm.state.value.stopwatchRunning }
    }

    @Test fun statsOpenFromTopBarIcon() {
        compose.onNodeWithTag("stats_button").performClick()
        compose.onNodeWithTag("stats_dialog").assertIsDisplayed()
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
