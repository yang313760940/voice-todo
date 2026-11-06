package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.voicetodo.speech.DoubaoSpeechSettings
import com.voicetodo.speech.FakeSpeechRecorder
import com.voicetodo.speech.FakeSpeechRecorderFactory
import com.voicetodo.speech.FakeSpeechStream
import com.voicetodo.speech.FakeSpeechStreamFactory
import com.voicetodo.speech.MemorySpeechSettingsStore
import com.voicetodo.speech.SpeechEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [33], application = Application::class)
class SpeechCaptureFlowTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var vm: TodoViewModel
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-06T01:00:00Z"))
    private val settingsStore = MemorySpeechSettingsStore()
    private val stream = FakeSpeechStream()
    private val streamFactory = FakeSpeechStreamFactory(stream)
    private val recorder = FakeSpeechRecorder()
    private val recorderFactory = FakeSpeechRecorderFactory(recorder)

    @Before
    fun setup() {
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
                    speechSettingsStore = settingsStore,
                    speechStreamFactory = streamFactory,
                    speechRecorderFactory = recorderFactory,
                    hasRecordAudioPermission = { true },
                ) as T
        })[TodoViewModel::class.java]
        waitFor { !vm.state.value.loading }
    }

    @After
    fun teardown() {
        store.clear()
        db.close()
    }

    @Test
    fun `voice entry streams partial text and waits for manual save`() {
        settingsStore.settings = configured()

        vm.onOpenAdd(startVoice = true)
        idle()
        assertTrue(recorder.started)
        assertTrue(stream.started)
        assertEquals(SpeechUiState.Connecting, vm.state.value.speechState)

        stream.emit(SpeechEvent.Partial("明天下午"))
        idle()
        assertEquals("明天下午", vm.state.value.draftText)
        assertEquals(SpeechUiState.Recording, vm.state.value.speechState)

        vm.onStopSpeech()
        idle()
        assertTrue(recorder.stopped)
        assertTrue(stream.finished)
        assertEquals(SpeechUiState.Finishing, vm.state.value.speechState)

        stream.emit(SpeechEvent.Final("明天下午三点开会"))
        idle()
        assertEquals("明天下午三点开会", vm.state.value.draftText)
        assertEquals(SpeechUiState.Idle, vm.state.value.speechState)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `failure keeps partial text and never auto saves or retries`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()

        stream.emit(SpeechEvent.Partial("买牛奶"))
        stream.emit(SpeechEvent.Failure("语音识别连接已中断，已识别的文字可能不完整", "log-1"))
        idle()

        assertEquals("买牛奶", vm.state.value.draftText)
        assertEquals(SpeechUiState.Failed, vm.state.value.speechState)
        assertTrue(vm.state.value.inputError!!.contains("可能不完整"))
        assertTrue(vm.state.value.inputError!!.contains("log-1"))
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse(stream.started.not())
    }

    @Test
    fun `missing key shows settings hint and does not open microphone`() {
        settingsStore.settings = null

        vm.onOpenAdd(startVoice = true)
        idle()

        assertFalse(recorder.started)
        assertFalse(stream.started)
        assertEquals(SpeechUiState.Idle, vm.state.value.speechState)
        assertTrue(vm.state.value.inputError!!.contains("API Key"))
    }

    @Test
    fun `first use asks for upload consent before recording`() {
        settingsStore.settings = DoubaoSpeechSettings("test-key", uploadAcknowledged = false)

        vm.onOpenAdd(startVoice = true)
        idle()
        assertTrue(vm.state.value.speechConsentVisible)
        assertFalse(recorder.started)

        vm.onConfirmSpeechConsent()
        idle()
        assertFalse(vm.state.value.speechConsentVisible)
        assertTrue(settingsStore.settings!!.uploadAcknowledged)
        assertTrue(recorder.started)
    }

    @Test
    fun `recorder chunks are forwarded to the speech stream`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()

        recorder.emit(byteArrayOf(1, 2, 3))
        idle()

        assertEquals(1, stream.chunks.size)
        assertTrue(stream.chunks.single().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `cancel keeps recognized text`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("保留这段字"))
        idle()

        vm.onCancelSpeech()
        idle()

        assertTrue(stream.cancelled)
        assertEquals("保留这段字", vm.state.value.draftText)
        assertEquals(SpeechUiState.Idle, vm.state.value.speechState)
    }

    @Test
    fun `add screen shows live partial text and manual stop control`() {
        settingsStore.settings = configured()
        compose.setContent { MaterialTheme { TodoScreen(vm) } }

        compose.runOnIdle { vm.onOpenAdd(startVoice = true) }
        compose.waitForIdle()
        stream.emit(SpeechEvent.Partial("实时字幕"))
        compose.waitForIdle()

        compose.onNodeWithTag("speech_partial_text").assertTextContains("实时字幕")
        compose.onNodeWithTag("speech_stop_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        assertTrue(stream.finished)
    }

    @Test
    fun `403 offers a manual switch to the legacy resource without auto retry`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(
            SpeechEvent.Failure("豆包语音鉴权失败（403）：资源未开通", "log-403"),
        )
        idle()

        assertTrue(vm.state.value.inputError!!.contains("403"))
        assertEquals(1, streamFactory.openCalls)

        vm.onSwitchToLegacySpeechResource()
        idle()

        assertEquals(
            DoubaoSpeechSettings.LEGACY_RESOURCE_ID,
            settingsStore.settings!!.resourceId,
        )
        assertEquals(SpeechUiState.Idle, vm.state.value.speechState)
        assertEquals(1, streamFactory.openCalls)
    }

    private fun configured() = DoubaoSpeechSettings(
        apiKey = "test-key",
        uploadAcknowledged = true,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        assertTrue("等待条件超时", condition())
    }
}
