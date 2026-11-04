package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
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
import com.voicetodo.ai.AiAssistException
import com.voicetodo.ai.AiDraft
import com.voicetodo.ai.AiReminderDraft
import com.voicetodo.ai.AiReminderKind
import com.voicetodo.ai.AiSettings
import com.voicetodo.ai.FakeAiAssistClient
import com.voicetodo.ai.MemoryAiSettingsStore
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.speech.AudioRecorder
import com.voicetodo.speech.OfflineTranscriber
import com.voicetodo.speech.TranscriptResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h1000dp")
class AiDraftFlowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var vm: TodoViewModel
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-04T09:00:00Z"))
    private val scheduler = RecordedScheduler()
    private val settingsStore = MemoryAiSettingsStore()
    private val aiClient = FakeAiAssistClient()
    private val key = "test" + "-key"
    private val settings = AiSettings("https://api.example.com/v1", "test-model", key)

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(
            db,
            FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")),
        )
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(
                    app = app,
                    useCases = TodoUseCases(repo, scheduler, clock),
                    storage = repo,
                    capabilities = { ReminderCapabilities(true, true, true) },
                    transcriber = object : OfflineTranscriber {
                        override fun isReady() = true
                        override fun transcribe(samples: FloatArray, sampleRate: Int) =
                            TranscriptResult.Success("测试识别")
                    },
                    clock = clock,
                    zoneId = ZoneId.of("Asia/Shanghai"),
                    recorder = object : AudioRecorder {
                        override val sampleRate = 16_000
                        override fun start() = true
                        override fun stop() = FloatArray(0)
                        override fun cancel() = Unit
                        override fun release() = Unit
                    },
                    aiSettingsStore = settingsStore,
                    aiClient = aiClient,
                ) as T
        })[TodoViewModel::class.java]
        compose.setContent { MaterialTheme { TodoScreen(vm) } }
        waitFor { !vm.state.value.loading }
    }

    @After fun teardown() {
        compose.runOnIdle { store.clear() }
        db.close()
    }

    @Test
    fun `ai failure keeps text creates nothing and never resends automatically`() {
        configureStore()
        aiClient.failure = AiAssistException("连接 AI 服务超时；文字已保留，可重试或手动创建")
        typeText("断网时保留这句话")

        clickAi()
        waitFor { vm.state.value.aiError != null && !vm.state.value.aiBusy }

        assertEquals("断网时保留这句话", vm.state.value.draftText)
        assertTrue(vm.state.value.aiError!!.contains("超时"))
        assertEquals(1, aiClient.calls)
        assertEquals(0, runBlocking { repo.all().size })
        assertTrue(scheduler.scheduled.isEmpty())

        compose.runOnIdle { vm.onRefresh() }
        compose.waitForIdle()
        assertEquals("刷新不能自动重发", 1, aiClient.calls)
    }

    @Test
    fun `draft shows source and creates only after explicit confirmation`() {
        configureStore()
        aiClient.result = AiDraft(
            sourceText = "明天下午交季度报告",
            title = "提交季度报告",
            plannedDate = "2026-11-05",
            dueAt = "2026-11-06 18:00",
            reminders = listOf(
                AiReminderDraft(kind = AiReminderKind.Specified, value = "2026-11-05 18:15"),
                AiReminderDraft(kind = AiReminderKind.Relative, value = "60"),
            ),
            quadrant = Quadrant.ImportantUrgent,
        )
        typeText("明天下午交季度报告")

        clickAi()
        waitFor { vm.state.value.aiDraft != null && !vm.state.value.aiBusy }

        compose.onNodeWithTag("ai_source_text").assertIsDisplayed()
        assertEquals(0, runBlocking { repo.all().size })
        assertTrue("确认前不能有闹钟", scheduler.scheduled.isEmpty())
        assertEquals("明天下午交季度报告", aiClient.receivedTexts.single())

        compose.onNodeWithTag("ai_draft_title_field").performTextReplacement("提交季度报告（确认）")
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            hasTestTag("ai_quadrant_NotImportantUrgent"),
        )
        compose.onNodeWithTag("ai_quadrant_NotImportantUrgent").performClick()
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            hasTestTag("ai_draft_confirm_button"),
        )
        compose.onNodeWithTag("ai_draft_confirm_button").performClick()
        waitFor { vm.state.value.items.size == 1 && vm.state.value.aiDraft == null }

        val saved = vm.state.value.items.single()
        assertEquals("提交季度报告（确认）", saved.title)
        assertEquals(Quadrant.NotImportantUrgent, saved.quadrant)
        assertEquals(2, saved.reminders.size)
        assertEquals(2, scheduler.scheduled.size)
        assertTrue(vm.state.value.draftText.isEmpty())
    }

    @Test
    fun `ambiguous time cannot be confirmed until the user explicitly skips a reminder`() {
        configureStore()
        aiClient.result = AiDraft(
            sourceText = "过几天交报告",
            title = "交报告",
            clarification = "时间含糊：请补充具体哪天和几点",
        )
        typeText("过几天交报告")

        clickAi()
        waitFor { vm.state.value.aiDraft != null }
        compose.onNodeWithTag("ai_draft_clarification").assertIsDisplayed()
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            hasTestTag("ai_draft_confirm_button"),
        )
        compose.onNodeWithTag("ai_draft_confirm_button").assertIsNotEnabled()

        compose.onNodeWithTag("ai_no_reminder_ack").performClick()
        compose.onNodeWithTag("ai_draft_confirm_button").assertIsEnabled().performClick()
        waitFor { vm.state.value.items.size == 1 }

        val saved = vm.state.value.items.single()
        assertTrue(saved.reminders.isEmpty())
        assertNull(saved.dueAt)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `unconfigured ai opens settings and manual creation still works`() {
        typeText("不配置 AI 也要能创建")
        clickAi()
        waitFor { vm.state.value.aiSettingsForm != null }
        compose.onNodeWithTag("ai_settings_screen").assertIsDisplayed()
        assertEquals(0, aiClient.calls)

        compose.onNodeWithTag("ai_settings_cancel").performClick()
        waitFor { vm.state.value.aiSettingsForm == null }
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("manual_create_button"))
        compose.onNodeWithTag("manual_create_button").performClick()
        waitFor { vm.state.value.detail != null }
        compose.onNodeWithTag("detail_screen").assertIsDisplayed()
    }

    @Test
    fun `cancelling an ai request ignores the late result and keeps text`() {
        configureStore()
        val gate = CompletableDeferred<Unit>()
        aiClient.gate = gate
        aiClient.result = AiDraft(sourceText = "取消也保留", title = "不应出现")
        typeText("取消也保留")

        clickAi()
        waitFor { vm.state.value.aiBusy }
        compose.runOnIdle { vm.onCancelAiRequest() }
        waitFor { !vm.state.value.aiBusy }
        assertEquals("取消也保留", vm.state.value.draftText)
        assertNull(vm.state.value.aiDraft)

        gate.complete(Unit)
        compose.waitForIdle()
        assertNull(vm.state.value.aiDraft)
        assertEquals(0, runBlocking { repo.all().size })
    }

    @Test
    fun `cancelling a draft keeps text and creates nothing`() {
        configureStore()
        aiClient.result = AiDraft(sourceText = "先看看草稿", title = "草稿")
        typeText("先看看草稿")
        clickAi()
        waitFor { vm.state.value.aiDraft != null }

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            hasTestTag("ai_draft_cancel_button"),
        )
        compose.onNodeWithTag("ai_draft_cancel_button").performClick()
        waitFor { vm.state.value.aiDraft == null }

        assertEquals("先看看草稿", vm.state.value.draftText)
        assertEquals(0, runBlocking { repo.all().size })
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `invalid edited field is rejected before anything is written`() {
        configureStore()
        aiClient.result = AiDraft(
            sourceText = "明天交报告",
            title = "交报告",
            dueAt = "2026-11-06 18:00",
        )
        typeText("明天交报告")
        clickAi()
        waitFor { vm.state.value.aiDraft != null }

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            hasTestTag("ai_draft_dueAt_field"),
        )
        compose.onNodeWithTag("ai_draft_dueAt_field").performTextReplacement("不是时间")
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(
            hasTestTag("ai_draft_confirm_button"),
        )
        compose.onNodeWithTag("ai_draft_confirm_button").performClick()
        waitFor { vm.state.value.aiError != null }
        compose.onNodeWithTag("ai_draft_error").assertIsDisplayed()

        assertEquals(0, runBlocking { repo.all().size })
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `settings screen saves the service and key through the store`() {
        compose.onNodeWithTag("ai_settings_button").performClick()
        waitFor { vm.state.value.aiSettingsForm != null }
        compose.onNodeWithTag("ai_base_url_field").performTextInput(settings.baseUrl)
        compose.onNodeWithTag("ai_model_field").performTextInput(settings.model)
        compose.onNodeWithTag("ai_api_key_field").performTextInput(key)
        compose.onNodeWithTag("ai_settings_save").performClick()
        waitFor { vm.state.value.aiSettingsForm == null && vm.state.value.aiConfigured }

        val saved = settingsStore.settings
        assertNotNull(saved)
        assertEquals(settings.baseUrl, saved!!.baseUrl)
        assertEquals(settings.model, saved.model)
        assertEquals(key, saved.apiKey)
    }

    private fun configureStore() {
        settingsStore.settings = settings
        compose.runOnIdle {
            vm.onOpenAiSettings()
            vm.onCloseAiSettings()
        }
    }

    private fun typeText(text: String) {
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("title_field"))
        compose.onNodeWithTag("title_field").performTextReplacement(text)
    }

    private fun clickAi() {
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("ai_organize_button"))
        compose.onNodeWithTag("ai_organize_button").performClick()
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
}
