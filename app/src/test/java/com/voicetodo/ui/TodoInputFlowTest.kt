package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.voicetodo.speech.AudioRecorder
import com.voicetodo.speech.OfflineTranscriber
import com.voicetodo.speech.TranscriptResult
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
    private val recorder = FakeAudioRecorder()
    private val transcriber = FakeTranscriber()
    private var canRecord = true

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
                    capabilities = { ReminderCapabilities(true, true, canRecord) },
                    transcriber = transcriber,
                    clock = clock,
                    zoneId = ZoneId.of("Asia/Shanghai"),
                    recorder = recorder,
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

    @Test fun recognitionFailureKeepsExistingTextAndReleasesTemporarySamples() {
        transcriber.result = TranscriptResult.Failure("固定失败")
        val samples = floatArrayOf(0.5f, -0.5f)
        recorder.nextSamples = samples

        compose.runOnIdle {
            vm.onDraftTextChange("已经取得的文字")
            vm.onToggleRecord()
            vm.onToggleRecord()
        }
        waitFor { vm.state.value.recordState == RecordState.Idle && vm.state.value.inputError != null }

        assertEquals("已经取得的文字", vm.state.value.draftText)
        assertTrue(vm.state.value.inputError!!.contains("固定失败"))
        assertTrue("转写结束后应清零临时采样", samples.all { it == 0f })
        assertEquals(1, recorder.startCount)
        assertEquals(1, recorder.stopCount)
    }

    @Test fun startThenEndTranscribesOnceAndAllowsASecondRecording() {
        recorder.nextSamples = floatArrayOf(0.4f)
        transcriber.result = TranscriptResult.Success("第一次识别")
        compose.runOnIdle {
            vm.onToggleRecord()
            vm.onToggleRecord()
        }
        waitFor { vm.state.value.recordState == RecordState.Idle && vm.state.value.draftText == "第一次识别" }

        compose.runOnIdle { vm.onToggleRecord() }
        assertEquals(RecordState.Recording, vm.state.value.recordState)
        assertEquals(2, recorder.startCount)
        assertEquals(1, recorder.stopCount)
        compose.runOnIdle { vm.onCancelRecording() }
    }

    @Test fun cancellingRecordingKeepsTextReleasesMicAndAllowsRecordingAgain() {
        compose.runOnIdle {
            vm.onDraftTextChange("保留的文字")
            vm.onToggleRecord()
            vm.onCancelRecording()
            vm.onToggleRecord()
        }

        assertEquals(RecordState.Recording, vm.state.value.recordState)
        assertEquals("保留的文字", vm.state.value.draftText)
        assertEquals(2, recorder.startCount)
        assertEquals(1, recorder.cancelCount)

        compose.runOnIdle { vm.onCancelRecording() }
        assertEquals(RecordState.Idle, vm.state.value.recordState)
        assertEquals(2, recorder.cancelCount)
    }

    @Test fun cancellingBeforeTranscriptionStartsStillClearsInjectedTemporaryAudio() {
        val samples = floatArrayOf(0.8f, -0.2f)
        compose.runOnIdle {
            vm.onDraftTextChange("取消识别也保留")
            vm.transcribeSamplesForTest(samples, 16_000)
            vm.onCancelRecording()
        }
        waitFor { samples.all { it == 0f } }
        assertEquals(RecordState.Idle, vm.state.value.recordState)
        assertEquals("取消识别也保留", vm.state.value.draftText)
    }

    @Test fun missingPermissionDisablesOnlyVoiceAndLeavesTextManualCreationUsable() {
        canRecord = false
        compose.runOnIdle { vm.refreshCapabilities() }

        compose.onNodeWithTag("record_permission_status").assertTextContains("麦克风 ✗", substring = true)
        compose.onNodeWithTag("voice_button").assertIsNotEnabled()
        compose.onNodeWithTag("title_field").assertIsEnabled().performTextInput("纯文字事项")
        compose.onNodeWithTag("manual_create_button").assertIsEnabled().performClick()
        compose.onNodeWithTag("detail_screen").assertIsDisplayed()
    }

    @Test fun missingBundledModelShowsExplicitOfflineErrorWithoutBlockingText() {
        transcriber.ready = false
        compose.runOnIdle { vm.refreshCapabilities() }

        compose.onNodeWithTag("voice_button").assertIsNotEnabled()
        compose.onNodeWithTag("todo_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("input_error"),
        )
        compose.onNodeWithTag("input_error")
            .assertTextContains("随包离线识别模型缺失或不完整", substring = true)
        compose.onNodeWithTag("todo_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("title_field"),
        )
        compose.onNodeWithTag("title_field").assertIsEnabled().performTextInput("模型缺失时仍可输入")
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

    private class FakeAudioRecorder : AudioRecorder {
        override val sampleRate = 16_000
        var nextSamples = floatArrayOf(0.25f)
        var active = false
        var startCount = 0
        var stopCount = 0
        var cancelCount = 0

        override fun start(): Boolean {
            if (active) return false
            active = true
            startCount++
            return true
        }

        override fun stop(): FloatArray {
            if (!active) return FloatArray(0)
            active = false
            stopCount++
            return nextSamples
        }

        override fun cancel() {
            if (active) {
                active = false
                cancelCount++
            }
        }

        override fun release() = cancel()
    }

    private class FakeTranscriber : OfflineTranscriber {
        var ready = true
        var result: TranscriptResult = TranscriptResult.Success("离线识别结果")
        override fun isReady() = ready
        override fun transcribe(samples: FloatArray, sampleRate: Int) = result
    }
}
