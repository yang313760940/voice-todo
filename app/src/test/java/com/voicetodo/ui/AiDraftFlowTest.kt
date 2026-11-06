package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import com.voicetodo.ai.AiAssistClient
import com.voicetodo.ai.AiDraft
import com.voicetodo.ai.AiPromptContext
import com.voicetodo.ai.AiRecurrenceDraft
import com.voicetodo.ai.AiReminderDraft
import com.voicetodo.ai.AiReminderKind
import com.voicetodo.ai.AiSettings
import com.voicetodo.ai.FakeAiAssistClient
import com.voicetodo.ai.LocalHttpServer
import com.voicetodo.ai.MemoryAiSettingsStore
import com.voicetodo.ai.OpenAiCompatibleClient
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.speech.DoubaoVoiceCredentials
import com.voicetodo.speech.MemorySpeechSettingsStore
import com.voicetodo.speech.VoiceProvider
import com.voicetodo.speech.VoiceServiceSettings
import java.time.Instant
import java.time.DayOfWeek
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var app: Application
    private lateinit var vm: TodoViewModel
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-04T09:00:00Z"))
    private val scheduler = RecordedScheduler()
    private val legacyAiStore = MemoryAiSettingsStore()
    private val voiceStore = MemorySpeechSettingsStore()
    private val aiClient = FakeAiAssistClient()
    private var activeAiClient: AiAssistClient? = null
    private val key = "test" + "-key"
    private val settings = VoiceServiceSettings(
        provider = VoiceProvider.Doubao,
        doubao = DoubaoVoiceCredentials(
            speechApiKey = "test" + "-speech-key",
            textApiKey = key,
            textModel = "test-model",
            textBaseUrl = "https://api.example.com/v1",
        ),
        uploadConsentVersion = VoiceServiceSettings.UPLOAD_CONSENT_VERSION,
        acknowledgedProviders = setOf(VoiceProvider.Doubao),
    )

    @Before fun setup() {
        activeAiClient = null
        app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(
            db,
            FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")),
        )
        vm = makeVm()
        compose.setContent { MaterialTheme { TodoScreen(vm) } }
        waitFor { !vm.state.value.loading }
        compose.runOnIdle { vm.onOpenAdd() }
        waitFor { vm.state.value.addOpen }
    }

    private fun makeVm(): TodoViewModel {
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(
                    app = app,
                    useCases = TodoUseCases(repo, scheduler, clock),
                    storage = repo,
                    capabilities = { ReminderCapabilities(true, true, true) },
                    clock = clock,
                    zoneId = ZoneId.of("Asia/Shanghai"),
                    aiSettingsStore = legacyAiStore,
                    speechSettingsStore = voiceStore,
                    aiClient = object : AiAssistClient by aiClient {
                        override suspend fun organizeDrafts(
                            text: String,
                            context: AiPromptContext,
                            settings: AiSettings,
                        ): List<AiDraft> =
                            (activeAiClient ?: aiClient).organizeDrafts(text, context, settings)
                    },
                ) as T
        })[TodoViewModel::class.java]
        return vm
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

        compose.onNodeWithTag("ai_settings_screen")
            .performScrollToNode(hasTestTag("ai_settings_cancel"))
        compose.onNodeWithTag("ai_settings_cancel").performClick()
        waitFor { vm.state.value.aiSettingsForm == null }
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("manual_create_button"))
        compose.onNodeWithTag("manual_create_button").performClick()
        waitFor { vm.state.value.detail != null }
        compose.onNodeWithTag("detail_screen").assertIsDisplayed()
    }

    @Test
    fun `legacy openai config no longer participates in voice organize`() {
        legacyAiStore.settings = AiSettings(
            "https://legacy.example.com/v1",
            "legacy-model",
            "test" + "-legacy-key",
        )
        typeText("旧配置不应参与语音整理")

        clickAi()

        waitFor { vm.state.value.aiSettingsForm != null }
        assertEquals(0, aiClient.calls)
        assertEquals(
            "https://legacy.example.com/v1",
            vm.state.value.aiSettingsForm!!.legacyBaseUrl,
        )
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
    fun `multi drafts can be selected edited deleted and confirmed atomically`() {
        configureStore()
        aiClient.results = listOf(
            AiDraft(sourceText = "明天买菜，每周一三五六点半锻炼，过几天交报告", title = "买菜",
                plannedDate = "2026-11-06"),
            AiDraft(sourceText = "明天买菜，每周一三五六点半锻炼，过几天交报告", title = "锻炼",
                recurrence = AiRecurrenceDraft(
                    frequency = RecurrenceFrequency.Weekly,
                    weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                    time = "18:30",
                )),
            AiDraft(sourceText = "明天买菜，每周一三五六点半锻炼，过几天交报告", title = "交报告",
                clarification = "请补充具体日期"),
        )
        typeText("明天买菜，每周一三五六点半锻炼，过几天交报告")

        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 3 && !vm.state.value.aiBusy }
        assertEquals(0, runBlocking { repo.all().size })
        assertTrue(scheduler.scheduled.isEmpty())

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_select_draft_1"))
        compose.onNodeWithTag("ai_select_draft_1").performClick()
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_title_field"))
        compose.onNodeWithTag("ai_draft_title_field").performTextReplacement("认真锻炼")
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_delete_draft_2"))
        compose.onNodeWithTag("ai_delete_draft_2").performClick()
        assertEquals(2, vm.state.value.aiDrafts.size)

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
        compose.onNodeWithTag("ai_draft_confirm_button").assertIsEnabled().performClick()
        compose.runOnIdle { vm.onConfirmAiDraft() }

        waitFor { vm.state.value.items.size == 3 && vm.state.value.aiDrafts.isEmpty() && !vm.state.value.busy }
        assertEquals(listOf("买菜"), vm.state.value.items.filter { it.seriesId == null }
            .map { it.title })
        val recurring = vm.state.value.items.filter { it.title == "认真锻炼" }
        assertEquals(2, recurring.size)
        assertTrue(recurring.all { it.seriesId != null })
        assertEquals(2, scheduler.scheduled.size)
        assertEquals(1, runBlocking { repo.all().count { it.seriesId == null && it.recurrence == null } })
    }

    @Test
    fun `http multi drafts support partial deletion and double confirmation without duplicates`() {
        withHttpDrafts {
            assertEquals(3, vm.state.value.aiDrafts.size)
            assertEquals(0, runBlocking { repo.all().size })
            assertTrue(scheduler.scheduled.isEmpty())
            compose.onNodeWithTag("ai_source_text").assertIsDisplayed()

            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_select_draft_1"))
            compose.onNodeWithTag("ai_select_draft_1").performClick()
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_title_field"))
            compose.onNodeWithTag("ai_draft_title_field").performTextReplacement("认真锻炼")
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_delete_draft_2"))
            compose.onNodeWithTag("ai_delete_draft_2").performClick()
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
            compose.onNodeWithTag("ai_draft_confirm_button").performClick()
            compose.runOnIdle { vm.onConfirmAiDraft() }

            waitFor { vm.state.value.items.size == 3 && vm.state.value.aiDrafts.isEmpty() && !vm.state.value.busy }
            assertEquals(listOf("买菜"), vm.state.value.items.filter { it.seriesId == null }.map { it.title })
            assertEquals(2, vm.state.value.items.count { it.title == "认真锻炼" })
            assertEquals(2, scheduler.scheduled.size)
        }
    }

    @Test
    fun `http batch waits for ambiguous draft and tomorrow only saves a planned date`() {
        withHttpDrafts {
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
            compose.onNodeWithTag("ai_draft_confirm_button").assertIsNotEnabled()
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_select_draft_2"))
            compose.onNodeWithTag("ai_select_draft_2").performClick()
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_no_reminder_ack"))
            compose.onNodeWithTag("ai_no_reminder_ack").performClick()
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
            compose.onNodeWithTag("ai_draft_confirm_button").assertIsEnabled().performClick()

            waitFor { vm.state.value.items.size == 4 && vm.state.value.aiDrafts.isEmpty() && !vm.state.value.busy }
            val groceries = vm.state.value.items.single { it.title == "买菜" }
            assertEquals(java.time.LocalDate.of(2026, 11, 5), groceries.plannedDate)
            assertNull(groceries.dueAt)
            assertNull(groceries.remindAt)
            assertTrue(groceries.reminders.isEmpty())
            assertEquals(2, scheduler.scheduled.size)
        }
    }

    @Test
    fun `http batch cancellation keeps original text and writes nothing`() {
        withHttpDrafts {
            val source = vm.state.value.draftText
            compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_cancel_button"))
            compose.onNodeWithTag("ai_draft_cancel_button").performClick()
            waitFor { vm.state.value.aiDraft == null }
            assertEquals(source, vm.state.value.draftText)
            assertEquals(0, runBlocking { repo.all().size })
            assertTrue(scheduler.scheduled.isEmpty())
        }
    }

    @Test
    fun `recurring suggestion can change frequency and month day before saving`() {
        configureStore()
        aiClient.results = listOf(
            AiDraft(sourceText = "每周一三五六点半锻炼", title = "锻炼",
                recurrence = AiRecurrenceDraft(
                    frequency = RecurrenceFrequency.Weekly,
                    weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                    time = "18:30",
                )),
        )
        typeText("每周一三五六点半锻炼")
        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 1 && !vm.state.value.aiBusy }

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_recurrence_Monthly"))
        compose.onNodeWithTag("ai_recurrence_Monthly").performClick()
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_recurrence_month_day"))
        compose.onNodeWithTag("ai_recurrence_month_day").performTextReplacement("31")
        assertEquals("18:30", vm.state.value.aiDraft!!.recurrence!!.time)
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
        compose.onNodeWithTag("ai_draft_confirm_button").performClick()

        waitFor { vm.state.value.items.size == 2 && vm.state.value.aiDrafts.isEmpty() && !vm.state.value.busy }
        val series = runBlocking { repo.all().single { it.recurrence != null } }
        assertEquals(RecurrenceFrequency.Monthly, series.recurrence!!.frequency)
        assertEquals(31, series.recurrence!!.dayOfMonth)
        assertTrue(series.recurrence!!.weekdays.isEmpty())
        assertEquals(java.time.LocalTime.of(18, 30), series.recurrence!!.reminderTimes.single())
    }

    @Test
    fun `matched list name and note survive confirmation`() {
        configureStore()
        compose.runOnIdle { vm.onCreateList("工作") }
        waitFor { vm.state.value.todoLists.any { it.name == "工作" } }
        val work = vm.state.value.todoLists.single { it.name == "工作" }
        aiClient.result = AiDraft(
            sourceText = "工作清单，明天上午十点提醒我交报告",
            title = "交报告",
            note = "带电脑",
            listName = "工作",
            reminders = listOf(
                AiReminderDraft(kind = AiReminderKind.Specified, value = "2026-11-05 10:00"),
            ),
        )
        typeText("工作清单，明天上午十点提醒我交报告")
        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 1 && !vm.state.value.aiBusy }
        assertEquals(work.id, vm.state.value.aiDraft!!.listId)

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_note_field"))
        compose.onNodeWithTag("ai_draft_note_field").performTextReplacement("带电脑和工牌")
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
        compose.onNodeWithTag("ai_draft_confirm_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }

        val saved = vm.state.value.items.single()
        assertEquals(work.id, saved.listId)
        assertEquals("带电脑和工牌", saved.note)
    }

    @Test
    fun `missing list falls back to inbox with a notice and keeps date and note`() {
        configureStore()
        aiClient.result = AiDraft(
            sourceText = "明天下午去不存在的清单买菜",
            title = "买菜",
            note = "下午去",
            listName = "不存在的清单",
            plannedDate = "2026-11-06",
            reminders = listOf(
                AiReminderDraft(kind = AiReminderKind.Specified, value = "2026-11-06 14:00"),
            ),
        )
        typeText("明天下午去不存在的清单买菜")
        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 1 && !vm.state.value.aiBusy }

        val draft = vm.state.value.aiDraft!!
        assertNull("匹配不到清单时归入收集箱", draft.listId)
        assertTrue(draft.listNotice!!.contains("未找到清单"))
        assertNull("清单提示不应阻塞保存", draft.clarification)

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
        compose.onNodeWithTag("ai_draft_confirm_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }

        val saved = vm.state.value.items.single()
        assertNull(saved.listId)
        assertEquals("下午去", saved.note)
        assertEquals(java.time.LocalDate.of(2026, 11, 6), saved.plannedDate)
        assertEquals(1, saved.reminders.size)
        assertNotNull(saved.remindAt)
    }

    @Test
    fun `matched list without a concrete reminder is forced into inbox`() {
        configureStore()
        compose.runOnIdle { vm.onCreateList("工作") }
        waitFor { vm.state.value.todoLists.any { it.name == "工作" } }
        val work = vm.state.value.todoLists.single { it.name == "工作" }
        aiClient.result = AiDraft(
            sourceText = "工作清单，明天处理",
            title = "处理资料",
            listName = "工作",
            plannedDate = "2026-11-06",
        )
        typeText("工作清单，明天处理")
        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 1 && !vm.state.value.aiBusy }

        assertNull("没有具体提醒时间时一律进收集箱", vm.state.value.aiDraft!!.listId)
        assertEquals("工作", vm.state.value.aiDraft!!.listName)
        assertNull(vm.state.value.aiDraft!!.listNotice)
        assertTrue(vm.state.value.todoLists.any { it.id == work.id })
    }

    @Test
    fun `inbox-all button saves ambiguous drafts without reminders`() {
        configureStore()
        aiClient.result = AiDraft(
            sourceText = "三点或者四点提醒我交报告",
            title = "交报告",
            clarification = "三点还是四点不明确，请确认",
        )
        typeText("三点或者四点提醒我交报告")
        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 1 && !vm.state.value.aiBusy }
        assertTrue(vm.state.value.aiDraft!!.requiresNoReminderAcknowledgement)
        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_inbox_all_button"))
        compose.onNodeWithTag("ai_inbox_all_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }

        val saved = vm.state.value.items.single()
        assertEquals("交报告", saved.title)
        assertNull(saved.listId)
        assertNull(saved.remindAt)
        assertTrue(saved.reminders.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `inbox-all button drops an invalid date and still saves the draft`() {
        configureStore()
        aiClient.result = AiDraft(
            sourceText = "2月30日聚会",
            title = "聚会",
            plannedDate = "2026-02-30",
            clarification = "计划日期无法识别，请修改",
        )
        typeText("2月30日聚会")
        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 1 && !vm.state.value.aiBusy }

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_inbox_all_button"))
        compose.onNodeWithTag("ai_inbox_all_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }

        val saved = vm.state.value.items.single()
        assertEquals("聚会", saved.title)
        assertNull(saved.plannedDate)
        assertNull(saved.listId)
        assertTrue(saved.reminders.isEmpty())
    }

    @Test
    fun `past reminder pauses the whole batch and marks the offending draft`() {
        configureStore()
        aiClient.results = listOf(
            AiDraft(
                sourceText = "十点交报告，明早八点买菜",
                title = "交报告",
                reminders = listOf(
                    AiReminderDraft(kind = AiReminderKind.Specified, value = "2026-11-05 10:00"),
                ),
            ),
            AiDraft(
                sourceText = "十点交报告，明早八点买菜",
                title = "买菜",
                reminders = listOf(
                    AiReminderDraft(kind = AiReminderKind.Specified, value = "2026-11-04 08:00"),
                ),
            ),
        )
        typeText("十点交报告，明早八点买菜")
        clickAi()
        waitFor { vm.state.value.aiDrafts.size == 2 && !vm.state.value.aiBusy }

        compose.onNodeWithTag("ai_draft_screen").performScrollToNode(hasTestTag("ai_draft_confirm_button"))
        compose.onNodeWithTag("ai_draft_confirm_button").performClick()
        waitFor { vm.state.value.aiError != null }

        assertTrue(vm.state.value.aiError!!.startsWith("第 2 条"))
        assertTrue(vm.state.value.aiError!!.contains("晚于当前时间"))
        assertEquals("买菜", vm.state.value.aiDraft!!.title)
        assertEquals(0, runBlocking { repo.all().size })
    }

    private fun withHttpDrafts(block: () -> Unit) {
        val content = """{"drafts":[
            {"title":"买菜","plannedDate":"2026-11-05"},
            {"title":"锻炼","recurrence":{
                "frequency":"WEEKLY","weekdays":[1,3,5],"time":"18:30"
            }},
            {"title":"交报告","clarification":"请补充具体日期"}
        ]}"""
        val response = JSONObject().put("choices", JSONArray().put(JSONObject().put(
            "message",
            JSONObject().put("content", content),
        ))).toString()
        LocalHttpServer(200, response).use { server ->
            configureStore()
            voiceStore.settings = settings.copy(
                doubao = settings.doubao.copy(
                    textBaseUrl = "http://127.0.0.1:${server.port}/v1",
                ),
            )
            activeAiClient = OpenAiCompatibleClient()
            typeText("明天买菜，每周一三五六点半锻炼，过几天交报告")
            clickAi()
            waitFor { vm.state.value.aiDrafts.size == 3 && !vm.state.value.aiBusy }
            block()
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `voice settings start unbound and only show the selected provider fields`() {
        compose.onNodeWithTag("ai_settings_button").performClick()
        waitFor { vm.state.value.aiSettingsForm != null }

        compose.onNodeWithTag("voice_provider_status")
            .assertTextContains("尚未选择供应商", substring = true)
        compose.onNodeWithTag("doubao_speech_api_key_field").assertDoesNotExist()
        compose.onNodeWithTag("aliyun_api_key_field").assertDoesNotExist()
        compose.onNodeWithTag("legacy_ai_settings_card").assertExists()

        compose.onNodeWithTag("voice_provider_Doubao").performClick()
        compose.onNodeWithTag("doubao_speech_api_key_field").assertExists()
        compose.onNodeWithTag("aliyun_api_key_field").assertDoesNotExist()
        compose.onNodeWithTag("voice_language_zh-CN").assertIsSelected()
        compose.onNodeWithTag("voice_language_yue-CN").assertExists()
        compose.onNodeWithTag("voice_language_fr-FR").assertDoesNotExist()

        compose.onNodeWithTag("voice_provider_Aliyun").performClick()
        compose.onNodeWithTag("aliyun_api_key_field").assertExists()
        compose.onNodeWithTag("doubao_speech_api_key_field").assertDoesNotExist()
    }

    @Test
    fun `settings screen saves provider language and credentials through the store`() {
        compose.onNodeWithTag("ai_settings_button").performClick()
        waitFor { vm.state.value.aiSettingsForm != null }
        compose.onNodeWithTag("voice_provider_Doubao").performClick()
        compose.onNodeWithTag("voice_language_yue-CN").performClick()
        compose.onNodeWithTag("ai_settings_screen")
            .performScrollToNode(hasTestTag("doubao_speech_api_key_field"))
        compose.onNodeWithTag("doubao_speech_api_key_field")
            .performTextInput(settings.doubao.speechApiKey)
        compose.onNodeWithTag("ai_settings_screen")
            .performScrollToNode(hasTestTag("doubao_text_api_key_field"))
        compose.onNodeWithTag("doubao_text_api_key_field").performTextInput(key)
        compose.onNodeWithTag("doubao_text_model_field").performTextInput("test-model")
        compose.onNodeWithTag("ai_settings_screen")
            .performScrollToNode(hasTestTag("voice_settings_save"))
        compose.onNodeWithTag("voice_settings_save").performClick()
        waitFor { vm.state.value.aiConfigured && vm.state.value.speechConfigured }

        val saved = voiceStore.settings
        assertNotNull(saved)
        assertEquals(VoiceProvider.Doubao, saved!!.provider)
        assertEquals("yue-CN", saved.language)
        assertEquals(settings.doubao.speechApiKey, saved.doubao.speechApiKey)
        assertEquals(key, saved.doubao.textApiKey)
        assertEquals("test-model", saved.doubao.textModel)
    }

    @Test
    fun `saved credentials are never echoed and blank fields keep them`() {
        voiceStore.settings = settings
        compose.runOnIdle { vm.onOpenAiSettings() }
        waitFor { vm.state.value.aiSettingsForm != null }

        assertEquals("", vm.state.value.aiSettingsForm!!.doubaoSpeechApiKey)
        assertEquals("", vm.state.value.aiSettingsForm!!.doubaoTextApiKey)
        compose.onNodeWithTag("ai_settings_screen")
            .performScrollToNode(hasTestTag("voice_settings_save"))
        compose.onNodeWithTag("voice_settings_save").performClick()
        waitFor { vm.state.value.aiConfigured && vm.state.value.speechConfigured }

        assertEquals(settings.doubao.speechApiKey, voiceStore.settings!!.doubao.speechApiKey)
        assertEquals(settings.doubao.textApiKey, voiceStore.settings!!.doubao.textApiKey)
    }

    @Test
    fun `retry after organize failure succeeds but never starts auto save`() {
        configureStore()
        aiClient.failure = AiAssistException("整理失败")
        typeText("整理失败后重试")
        clickAi()
        waitFor { vm.state.value.aiError != null && !vm.state.value.aiBusy }

        aiClient.failure = null
        aiClient.result = AiDraft(
            sourceText = "整理失败后重试",
            title = "重试成功",
            plannedDate = "2026-11-07",
        )
        clickAi()
        waitFor { vm.state.value.aiDrafts.isNotEmpty() && !vm.state.value.aiBusy }

        assertNull(vm.state.value.autoSaveDeadline)
        clock.instant = clock.instant.plusMillis(2_100)
        vm.onAutoSaveTick()
        compose.waitForIdle()
        assertEquals(0, runBlocking { repo.all().size })
    }

    @Test
    fun `restart does not restore unsaved text or drafts`() {
        configureStore()
        aiClient.result = AiDraft(
            sourceText = "未保存的草稿",
            title = "未保存",
            plannedDate = "2026-11-07",
        )
        typeText("未保存的草稿")
        clickAi()
        waitFor { vm.state.value.aiDrafts.isNotEmpty() && !vm.state.value.aiBusy }

        compose.runOnIdle { store.clear() }
        val restarted = makeVm()
        waitFor { !restarted.state.value.loading }

        assertFalse(restarted.state.value.addOpen)
        assertNull(restarted.state.value.detail)
        assertTrue(restarted.state.value.aiDrafts.isEmpty())
        assertTrue(restarted.state.value.draftText.isBlank())
        assertNull(restarted.state.value.message)
        assertEquals(0, runBlocking { repo.all().size })
    }

    private fun configureStore() {
        voiceStore.settings = settings
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
