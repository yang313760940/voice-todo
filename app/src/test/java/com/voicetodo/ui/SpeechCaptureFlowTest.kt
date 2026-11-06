package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.ai.AiDraft
import com.voicetodo.ai.AiSettings
import com.voicetodo.ai.FakeAiAssistClient
import com.voicetodo.ai.MemoryAiSettingsStore
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
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val aiSettingsStore = MemoryAiSettingsStore()
    private val aiClient = FakeAiAssistClient()
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
                    aiSettingsStore = aiSettingsStore,
                    aiClient = aiClient,
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

        compose.onNodeWithTag("voice_partial_text").assertTextContains("实时字幕")
        compose.onNodeWithTag("voice_stop_button")
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

    @Test
    fun `final transcription automatically organizes and saves once after two seconds`() {
        settingsStore.settings = configured()
        aiSettingsStore.settings = AiSettings(
            "https://api.example.com/v1", "test-model", "test" + "-key",
        )
        aiClient.result = AiDraft(
            sourceText = "明天下午开会",
            title = "开会",
            note = "下午，三楼会议室",
            plannedDate = "2026-11-07",
        )

        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("明天下午开会"))
        idle()
        vm.onStopSpeech()
        idle()
        stream.emit(SpeechEvent.Final("明天下午开会"))
        idle()
        waitFor { vm.state.value.aiDrafts.size == 1 && !vm.state.value.aiBusy }

        assertEquals("识别完成后自动整理一次", 1, aiClient.calls)
        assertEquals("明天下午开会", vm.state.value.draftText)
        assertEquals("下午，三楼会议室", vm.state.value.aiDraft!!.note)
        assertTrue("倒计时期间尚未保存", vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.autoSaveDeadline != null)

        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }

        val saved = vm.state.value.items.single()
        assertEquals("开会", saved.title)
        assertEquals(LocalDate.of(2026, 11, 7), saved.plannedDate)
        assertTrue(saved.reminders.isEmpty())
        assertFalse(vm.state.value.voicePanelOpen)
        assertNull(vm.state.value.autoSaveDeadline)

        vm.onAutoSaveTick()
        waitFor { !vm.state.value.busy }
        assertEquals("只自动保存一次", 1, vm.state.value.items.size)
    }

    @Test
    fun `two seconds of silence after speech ends recording automatically`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("明天开会"))
        idle()

        clock.instant = clock.instant.plusMillis(2_100)
        waitFor { stream.finished }

        assertTrue(recorder.stopped)
        assertTrue(vm.state.value.message!!.contains("连续 2 秒"))
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `ten seconds without speech stops without creating an item`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()

        clock.instant = clock.instant.plusMillis(10_100)
        waitFor { vm.state.value.message?.contains("10 秒") == true }

        assertFalse(vm.state.value.voiceHasSpoken)
        assertTrue(vm.state.value.voicePanelOpen)
        assertTrue(recorder.stopped)
        assertTrue(stream.cancelled)
        assertFalse(stream.finished)
        assertTrue(vm.state.value.message!!.contains("10 秒"))
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `sixty second limit stops recording and keeps a manual review warning`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("正在说一件很长的事"))
        idle()

        clock.instant = clock.instant.plusMillis(60_100)
        waitFor { stream.finished }

        assertTrue(vm.state.value.voiceLimitReached)
        assertTrue(vm.state.value.message!!.contains("60 秒录音上限"))
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `pen stops recording and protects edited text from the final transcript`() {
        settingsStore.settings = configured()
        compose.setContent { MaterialTheme { TodoScreen(vm) } }
        compose.runOnIdle { vm.onOpenAdd(startVoice = true) }
        compose.waitForIdle()
        stream.emit(SpeechEvent.Partial("识别错的字"))
        compose.waitForIdle()

        compose.onNodeWithTag("voice_edit_pen").performClick()
        compose.onNodeWithTag("voice_edit_text").performTextReplacement("我自己改的字")
        compose.waitForIdle()
        assertTrue(recorder.stopped)
        assertTrue(stream.finished)

        stream.emit(SpeechEvent.Final("服务返回的另一句话"))
        compose.waitForIdle()
        assertEquals("我自己改的字", vm.state.value.draftText)
        assertTrue(vm.state.value.voiceTextEditing)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `close confirmation can continue editing or discard the whole voice draft`() {
        settingsStore.settings = configured()
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("尚未保存的内容"))
        idle()

        vm.onRequestCloseVoicePanel()
        idle()
        assertTrue(vm.state.value.voiceDiscardConfirmVisible)

        vm.onContinueVoiceEditing()
        idle()
        assertTrue(vm.state.value.voicePanelOpen)
        assertEquals("尚未保存的内容", vm.state.value.draftText)
        assertEquals(SpeechUiState.Idle, vm.state.value.speechState)
        assertTrue(stream.cancelled)
        assertFalse(vm.state.value.voiceDiscardConfirmVisible)

        vm.onRequestCloseVoicePanel()
        idle()
        vm.onDiscardVoiceDraft()
        idle()
        assertFalse(vm.state.value.addOpen)
        assertFalse(vm.state.value.voicePanelOpen)
        assertTrue(vm.state.value.draftText.isEmpty())
    }

    @Test
    fun `bottom microphone appears only on todo and short press opens the voice panel`() {
        settingsStore.settings = configured()
        compose.setContent {
            MaterialTheme {
                VoiceTodoNavigationHost(
                    todoContent = { TodoScreen(vm) },
                    quadrantContent = { Text("四象限页") },
                    pomodoroContent = { Text("番茄页") },
                    settingsContent = { Text("我的页") },
                )
            }
        }

        compose.onNodeWithTag("voice_mic_button").assertExists()
        compose.onNodeWithTag("voice_add_button").assertDoesNotExist()
        compose.onNodeWithTag("text_add_button").assertDoesNotExist()
        compose.onNodeWithTag("voice_mic_button").performTouchInput { click() }
        waitFor { vm.state.value.addOpen && vm.state.value.voicePanelOpen }
        compose.onNodeWithTag("voice_panel").assertExists()
        compose.onNodeWithTag("voice_edit_pen").assertExists()
        compose.onNodeWithTag("voice_waveform").assertExists()
        compose.onNodeWithTag("voice_duration").assertExists()

        compose.onNodeWithTag("voice_close_button").performClick()
        waitFor { !vm.state.value.voicePanelOpen }
        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("voice_mic_button").assertDoesNotExist()
    }

    @Test
    fun `long press microphone opens the full manual form`() {
        compose.setContent { MaterialTheme { TodoScreen(vm) } }

        compose.onNodeWithTag("voice_mic_button").performTouchInput { longClick() }
        waitFor { vm.state.value.detail != null }

        assertFalse(vm.state.value.voicePanelOpen)
        assertFalse(vm.state.value.addOpen)
        compose.onNodeWithTag("detail_title_field").assertExists()
        compose.onNodeWithTag("detail_screen").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("detail_list_selector"),
        )
        compose.onNodeWithTag("detail_list_selector").assertExists()
        compose.onNodeWithTag("detail_screen").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("repeat_none"),
        )
        compose.onNodeWithTag("repeat_none").assertExists()
    }

    @Test
    fun `editing a draft cancels auto save and manual save still works`() {
        compose.setContent { MaterialTheme { TodoScreen(vm) } }
        prepareAutoSaveDraft("开会")
        compose.onNodeWithTag("ai_autosave_countdown").assertExists()

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("ai_draft_title_field"),
        )
        compose.onNodeWithTag("ai_draft_title_field").performTextReplacement("改过的开会")
        waitFor { vm.state.value.autoSaveDeadline == null }

        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        idle()
        assertTrue(vm.state.value.items.isEmpty())

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("ai_draft_confirm_button"),
        )
        compose.onNodeWithTag("ai_draft_confirm_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }
        assertEquals("改过的开会", vm.state.value.items.single().title)
    }

    @Test
    fun `clicking countdown cancels auto save without restarting`() {
        compose.setContent { MaterialTheme { TodoScreen(vm) } }
        prepareAutoSaveDraft("倒计时")

        compose.onNodeWithTag("ai_autosave_countdown").performClick()
        waitFor { vm.state.value.autoSaveDeadline == null }
        assertTrue(vm.state.value.message!!.contains("已取消自动保存"))

        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        idle()
        assertTrue(vm.state.value.items.isEmpty())
        assertNull(vm.state.value.autoSaveDeadline)
    }

    @Test
    fun `close after countdown still asks to discard and cancels auto save`() {
        prepareAutoSaveDraft("关闭确认")
        vm.onRequestCloseVoicePanel()
        idle()

        assertNull(vm.state.value.autoSaveDeadline)
        assertTrue(vm.state.value.voiceDiscardConfirmVisible)
        assertTrue(vm.state.value.aiDrafts.isNotEmpty())

        vm.onContinueVoiceEditing()
        idle()
        assertTrue(vm.state.value.voicePanelOpen)
        assertTrue(vm.state.value.aiDrafts.isNotEmpty())
        assertNull(vm.state.value.autoSaveDeadline)
    }

    @Test
    fun `tab switch cancels auto save before asking to discard`() {
        prepareAutoSaveDraft("切 tab")
        vm.onTabSwitchAttempt()
        idle()
        assertNull(vm.state.value.autoSaveDeadline)
        assertTrue(vm.state.value.aiDrafts.isNotEmpty())
        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        idle()
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `app background cancels auto save and keeps obtained text`() {
        prepareAutoSaveDraft("切后台")
        vm.onAppBackgrounded()
        idle()
        assertNull(vm.state.value.autoSaveDeadline)
        assertEquals("明天开会", vm.state.value.draftText)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `sixty second limit never starts auto save even when organize succeeds`() {
        settingsStore.settings = configured()
        aiSettingsStore.settings = AiSettings(
            "https://api.example.com/v1", "test-model", "test" + "-key",
        )
        aiClient.result = AiDraft(
            sourceText = "说满六十秒",
            title = "长录音",
            plannedDate = "2026-11-07",
        )
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("说满六十秒"))
        clock.instant = clock.instant.plusMillis(60_100)
        waitFor { stream.finished }
        stream.emit(SpeechEvent.Final("说满六十秒"))
        idle()
        waitFor { vm.state.value.aiDrafts.isNotEmpty() && !vm.state.value.aiBusy }

        assertTrue(vm.state.value.voiceLimitReached)
        assertNull(vm.state.value.autoSaveDeadline)
        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        idle()
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `organize failure never starts auto save`() {
        settingsStore.settings = configured()
        aiSettingsStore.settings = AiSettings(
            "https://api.example.com/v1", "test-model", "test" + "-key",
        )
        aiClient.failure = com.voicetodo.ai.AiAssistException("整理失败")
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("整理失败的话"))
        vm.onStopSpeech()
        idle()
        stream.emit(SpeechEvent.Final("整理失败的话"))
        idle()
        waitFor { vm.state.value.aiError != null && !vm.state.value.aiBusy }

        assertNull(vm.state.value.autoSaveDeadline)
        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        idle()
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `blocked draft pauses auto save until the user confirms`() {
        settingsStore.settings = configured()
        aiSettingsStore.settings = AiSettings(
            "https://api.example.com/v1", "test-model", "test" + "-key",
        )
        aiClient.result = AiDraft(
            sourceText = "三点或者四点开会",
            title = "开会",
            plannedDate = "2026-11-07",
            clarification = "时间不确定：三点还是四点？",
        )
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("三点或者四点开会"))
        vm.onStopSpeech()
        idle()
        stream.emit(SpeechEvent.Final("三点或者四点开会"))
        idle()
        waitFor { vm.state.value.aiDrafts.isNotEmpty() && !vm.state.value.aiBusy }

        assertNull(vm.state.value.autoSaveDeadline)
        assertTrue(vm.state.value.aiError!!.contains("暂停自动保存"))
        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        idle()
        assertTrue(vm.state.value.items.isEmpty())
    }

    private fun prepareAutoSaveDraft(title: String) {
        settingsStore.settings = configured()
        aiSettingsStore.settings = AiSettings(
            "https://api.example.com/v1", "test-model", "test" + "-key",
        )
        aiClient.result = AiDraft(
            sourceText = "明天开会",
            title = title,
            plannedDate = "2026-11-07",
        )
        vm.onOpenAdd(startVoice = true)
        idle()
        stream.emit(SpeechEvent.Partial("明天开会"))
        vm.onStopSpeech()
        idle()
        stream.emit(SpeechEvent.Final("明天开会"))
        idle()
        waitFor { vm.state.value.aiDrafts.isNotEmpty() && vm.state.value.autoSaveDeadline != null }
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
