package com.voicetodo.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.data.*
import com.voicetodo.domain.*
import com.voicetodo.speech.OfflineTranscriber
import com.voicetodo.speech.TranscriptResult
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import java.time.Instant
import java.time.Duration
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h1000dp")
class TodoScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private lateinit var db: TodoDatabase
    private val previousDatabases = mutableListOf<TodoDatabase>()
    private lateinit var repo: RoomTodoRepository
    private lateinit var uc: TodoUseCases
    private lateinit var vm: TodoViewModel
    private var visibleVm by mutableStateOf<TodoViewModel?>(null)
    private val store = ViewModelStore()
    private val clock = FrozenClock(Instant.parse("2026-11-04T09:00:00Z"))
    private val scheduler = RecordedScheduler()

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), ZoneId.of("Asia/Shanghai")))
        uc = TodoUseCases(repo, scheduler, clock)
        compose.runOnIdle { vm = makeViewModel(app); visibleVm = vm }
        compose.setContent { MaterialTheme { visibleVm?.let { key(it) { TodoScreen(it) } } } }
        waitFor { !vm.state.value.loading }
    }

    private fun makeViewModel(app: Application = ApplicationProvider.getApplicationContext()): TodoViewModel =
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    TodoViewModel(app, uc, repo, { ReminderCapabilities(true, true, true) },
                        object : OfflineTranscriber {
                            override fun isReady() = true
                            override fun transcribe(samples: FloatArray, sampleRate: Int) = TranscriptResult.Success("From speech")
                        }, clock, ZoneId.of("Asia/Shanghai")) as T
            })[TodoViewModel::class.java]

    @After fun teardown() {
        compose.runOnIdle { store.clear() }
        db.close()
        previousDatabases.forEach { it.close() }
    }

    @Test fun savingWithoutReminderCreatesAnUntimedItemAndGivesDistinctFeedback() {
        openAdd()
        compose.onNodeWithTag("title_field").performTextInput("Untimed")
        compose.onNodeWithTag("save_without_reminder_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }
        compose.onNodeWithTag("message").assertTextEquals("事项已保存（未设置提醒）")
        Assert.assertNull(runBlocking { repo.all().single().remindAt })
        Assert.assertTrue(scheduler.scheduled.isEmpty())
    }

    private fun createUntimed(title: String = "Original"): TodoItem {
        openAdd()
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("title_field"))
        compose.onNodeWithTag("title_field").performTextReplacement(title)
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("save_without_reminder_button"))
        compose.onNodeWithTag("save_without_reminder_button").performClick()
        waitFor { vm.state.value.items.any { it.title == title } && !vm.state.value.busy }
        return vm.state.value.items.single { it.title == title }
    }

    private fun open(item: TodoItem, completed: Boolean = false) {
        compose.runOnIdle {
            vm.onSelectView(if (completed) TodoView.Completed else TodoView.All)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag(if (completed) "completed_row_0" else "item_row_0"))
        compose.onNodeWithTag("open_${item.id}").performClick()
        waitFor { vm.state.value.detail?.original?.id == item.id && !vm.state.value.busy }
    }

    private fun detailClick(tag: String) {
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun replaceDetail(tag: String, text: String) {
        compose.onNodeWithTag("detail_screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextReplacement(text)
    }

    private fun assertFieldText(tag: String, text: String) {
        Assert.assertEquals(text, compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun saveDetail() {
        detailClick("detail_save_button")
        waitFor { vm.state.value.detail == null && !vm.state.value.busy }
    }

    @Test fun editingIndependentFieldsSurvivesDetailReopeningAndViewModelRecreation() {
        val item = createUntimed()
        open(item)
        replaceDetail("detail_title_field", " Edited ")
        replaceDetail("detail_plannedDate_field", "2026-11-05")
        replaceDetail("detail_dueAt_field", "2026-11-06 20:30")
        replaceDetail("detail_reminder_value_0", "2026-11-05 18:15")
        saveDetail()
        val saved = runBlocking { repo.findById(item.id)!! }
        Assert.assertEquals("Edited", saved.title)
        Assert.assertEquals(LocalDate.of(2026, 11, 5), saved.plannedDate)
        Assert.assertEquals(Instant.parse("2026-11-06T12:30:00Z"), saved.dueAt)
        Assert.assertEquals(Instant.parse("2026-11-05T10:15:00Z"), saved.remindAt)
        compose.runOnIdle { store.clear(); vm = makeViewModel(); visibleVm = vm }
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.loading }
        open(saved)
        assertFieldText("detail_title_field", "Edited")
        assertFieldText("detail_plannedDate_field", "2026-11-05")
        assertFieldText("detail_dueAt_field", "2026-11-06 20:30")
        assertFieldText("detail_reminder_value_0", "2026-11-05 18:15")
    }

    @Test fun cancelDiscardsEditsAndInvalidInputCannotPartiallySave() {
        val item = createUntimed()
        open(item)
        replaceDetail("detail_title_field", "Discard")
        replaceDetail("detail_dueAt_field", "2026-11-05 11:00")
        detailClick("detail_cancel_button")
        Assert.assertEquals(item, runBlocking { repo.findById(item.id) })
        open(item)
        assertFieldText("detail_title_field", "Original")
        replaceDetail("detail_plannedDate_field", "invalid-date")
        detailClick("detail_save_button")
        waitFor { !vm.state.value.busy && vm.state.value.message != null }
        Assert.assertNotNull(vm.state.value.detail)
        Assert.assertEquals(item, runBlocking { repo.findById(item.id) })
        compose.onNodeWithTag("message").assertTextContains("日期格式", substring = true)
    }

    @Test fun completingAndRestoringFutureReminderMovesBetweenExplicitLists() {
        val item = createUntimed()
        open(item)
        replaceDetail("detail_reminder_value_0", "2026-11-05 18:15")
        saveDetail()
        open(item)
        detailClick("detail_complete_button")
        waitFor { vm.state.value.items.isEmpty() && vm.state.value.completedItems.size == 1 && !vm.state.value.busy }
        Assert.assertEquals(listOf(item.id), scheduler.cancelled)
        open(item, completed = true)
        compose.onNodeWithTag("detail_title_field").assertIsNotEnabled()
        detailClick("detail_restore_button")
        waitFor { vm.state.value.items.size == 1 && vm.state.value.completedItems.isEmpty() && !vm.state.value.busy }
        Assert.assertEquals(3L, vm.state.value.items.single().reminderRevision)
        Assert.assertEquals(2, scheduler.scheduled.size)
        Assert.assertEquals(3L, scheduler.scheduled.last().revision)
    }

    @Test fun expiredRestoreClearsReminderAndDoesNotRepeatWarningAfterReopening() {
        val item = createUntimed()
        open(item)
        replaceDetail("detail_reminder_value_0", "2026-11-04 17:01")
        saveDetail()
        open(item)
        detailClick("detail_complete_button")
        waitFor { vm.state.value.completedItems.size == 1 && !vm.state.value.busy }
        clock.instant = Instant.parse("2026-11-04T09:02:00Z")
        open(item, completed = true)
        detailClick("detail_restore_button")
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }
        Assert.assertNull(vm.state.value.items.single().remindAt)
        Assert.assertEquals("旧提醒已过期，已清除；请重新安排提醒", vm.state.value.message)
        Assert.assertEquals(1, scheduler.scheduled.size)
        compose.runOnIdle { store.clear(); vm = makeViewModel(); visibleVm = vm }
        waitFor { !vm.state.value.loading }
        Assert.assertNull(vm.state.value.message)
        open(item)
        assertFieldText("detail_reminder_value_0", "")
    }

    @Test fun deliveredAndOverdueAreIndependentAndOldRevisionBadgeDisappears() {
        val at = clock.now().minusSeconds(60)
        val item = TodoItem(id = "states", title = "States", remindAt = at, dueAt = at,
            createdAt = clock.now())
        runBlocking {
            repo.save(item)
            Assert.assertTrue(repo.deliverReminder(item.id, 0, at.toEpochMilli(), clock::now) { true })
        }
        compose.runOnIdle { vm.onRefresh() }
        waitFor { item.id in vm.state.value.deliveredIds }
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("item_row_0"))
        compose.onNodeWithTag("delivered_${item.id}", useUnmergedTree = true).assertTextEquals("已提醒")
        compose.onNodeWithTag("overdue_${item.id}", useUnmergedTree = true).assertTextEquals("逾期")
        open(item)
        detailClick("clear_dueAt")
        replaceDetail("detail_reminder_value_0", "2026-11-05 18:15")
        saveDetail()
        Assert.assertTrue(vm.state.value.deliveredIds.isEmpty())
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("item_row_0"))
        compose.onNodeWithTag("delivered_${item.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("overdue_${item.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun speechDraftCanBeEditedAndManualReminderFeedbackDistinguishesSchedulerFailure() {
        openAdd()
        compose.runOnIdle { vm.transcribeSamplesForTest(floatArrayOf(0f), 16000) }
        waitFor { vm.state.value.draftText == "From speech" }
        compose.onNodeWithTag("title_field").performTextReplacement("Edited speech")
        compose.onNodeWithTag("hour_field").performTextInput("18")
        compose.onNodeWithTag("minute_field").performTextInput("30")
        compose.onNodeWithTag("pick_time_button").performClick()
        compose.onNodeWithTag("save_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }
        Assert.assertEquals("已保存并安排提醒", vm.state.value.message)
        Assert.assertEquals("Edited speech", vm.state.value.items.single().title)
        scheduler.scheduleSucceeds = false
        openAdd()
        compose.onNodeWithTag("title_field").performTextReplacement("Not ready")
        compose.onNodeWithTag("hour_field").performTextInput("18")
        compose.onNodeWithTag("minute_field").performTextInput("30")
        compose.onNodeWithTag("pick_time_button").performClick()
        compose.onNodeWithTag("save_button").performClick()
        waitFor { vm.state.value.items.size == 2 && !vm.state.value.busy }
        Assert.assertEquals("事项已保存，但提醒未就绪（缺精确闹钟权限或系统限制）", vm.state.value.message)
    }

    @Test fun nativePickersAndClearButtonsKeepAllThreeTimesIndependent() {
        val item = createUntimed()
        open(item)
        fun pickDate(tag: String, day: Int, time: Boolean) {
            detailClick("pick_$tag")
            compose.runOnIdle {
                val dialog = ShadowDialog.getLatestDialog() as DatePickerDialog
                dialog.datePicker.updateDate(2026, 10, day)
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                if (time) {
                    val timeDialog = ShadowDialog.getLatestDialog() as TimePickerDialog
                    timeDialog.updateTime(18, 30)
                    timeDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
        }
        pickDate("plannedDate", 5, false)
        pickDate("dueAt", 6, true)
        pickDate("reminder_0", 7, true)
        saveDetail()
        var saved = runBlocking { repo.findById(item.id)!! }
        Assert.assertEquals(LocalDate.of(2026, 11, 5), saved.plannedDate)
        Assert.assertEquals(Instant.parse("2026-11-06T10:30:00Z"), saved.dueAt)
        Assert.assertEquals(Instant.parse("2026-11-07T10:30:00Z"), saved.remindAt)
        open(item)
        detailClick("clear_plannedDate")
        detailClick("clear_dueAt")
        detailClick("clear_remindAt")
        saveDetail()
        saved = runBlocking { repo.findById(item.id)!! }
        Assert.assertNull(saved.plannedDate)
        Assert.assertNull(saved.dueAt)
        Assert.assertNull(saved.remindAt)
        Assert.assertEquals(2L, saved.reminderRevision)
        Assert.assertEquals(1, scheduler.scheduled.size)
        Assert.assertEquals("事项已保存（未设置提醒）", vm.state.value.message)
    }

    @Test fun editingOnlyTitleInDetailPreservesAnExpiredSubsecondReminderWithoutRescheduling() {
        val at = clock.now().plusSeconds(60).plusNanos(123)
        val item = runBlocking { uc.confirmDraft("Original", at).item }
        clock.instant = at.plusSeconds(1)
        compose.runOnIdle { vm.onRefresh() }
        waitFor { vm.state.value.items.size == 1 }
        open(item)
        replaceDetail("detail_title_field", "New title")
        saveDetail()
        val saved = runBlocking { repo.findById(item.id)!! }
        Assert.assertEquals("New title", saved.title)
        Assert.assertEquals(at, saved.remindAt)
        Assert.assertEquals(0L, saved.reminderRevision)
        Assert.assertEquals(1, scheduler.scheduled.size)
        Assert.assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowScreenCanReachDetailSaveCompleteAndRestore() {
        val item = createUntimed()
        open(item)
        replaceDetail("detail_plannedDate_field", "2026-11-05")
        saveDetail()
        open(item)
        detailClick("detail_complete_button")
        waitFor { vm.state.value.completedItems.size == 1 && !vm.state.value.busy }
        open(item, completed = true)
        detailClick("detail_restore_button")
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }
        Assert.assertEquals(LocalDate.of(2026, 11, 5), vm.state.value.items.single().plannedDate)
    }

    @Test fun migrationFailureDisablesSaveAndRetainsOriginalFileUntilExplicitRetry() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val legacyDir = temp.newFolder()
        val file = File(legacyDir, "todos.json").apply { writeText("[broken") }
        compose.runOnIdle {
            store.clear()
            previousDatabases += db
            db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
            repo = RoomTodoRepository(db, FileTodoRepository(legacyDir, ZoneId.of("Asia/Shanghai")))
            uc = TodoUseCases(repo, scheduler, clock)
            vm = makeViewModel(app)
            visibleVm = vm
        }
        waitFor { vm.state.value.storageError != null && !vm.state.value.loading }
        compose.onNodeWithTag("storage_error").assertTextEquals("数据迁移失败，旧文件已保留；修复后可重试")
        compose.runOnIdle { vm.onOpenAdd() }
        compose.waitForIdle()
        compose.onNodeWithTag("save_button").assertIsNotEnabled()
        Assert.assertEquals("[broken", file.readText())
        Assert.assertTrue(vm.state.value.items.isEmpty())
        val original = """[{"id":"recoverable","title":"Recovered","createdAt":"2026-11-04T08:00:00"}]"""
        file.writeText(original)
        compose.onNodeWithTag("retry_storage_button").performClick()
        waitFor { vm.state.value.items.size == 1 && !vm.state.value.busy }
        Assert.assertNull(vm.state.value.storageError)
        Assert.assertEquals("Recovered", vm.state.value.items.single().title)
        Assert.assertEquals(original, file.readText())
        compose.onNodeWithTag("save_button").assertIsEnabled()
    }

    @Test fun detailEditsMultipleReminderRulesAndRepairsNotReadySchedules() {
        val item = createUntimed("Multiple reminders")
        scheduler.scheduleSucceeds = false
        open(item)
        replaceDetail("detail_dueAt_field", "2026-11-05 20:00")
        replaceDetail("detail_reminder_value_0", "2026-11-05 18:00")
        detailClick("add_specified_reminder")
        replaceDetail("detail_reminder_value_1", "2026-11-05 19:00")
        detailClick("add_relative_reminder")
        replaceDetail("detail_reminder_value_2", "60")
        saveDetail()

        val saved = runBlocking { repo.findById(item.id)!! }
        Assert.assertEquals(3, saved.reminders.size)
        Assert.assertEquals(
            listOf(
                Instant.parse("2026-11-05T10:00:00Z") to null,
                Instant.parse("2026-11-05T11:00:00Z") to null,
                Instant.parse("2026-11-05T11:00:00Z") to Duration.ofMinutes(60),
            ),
            saved.reminders.map { it.at to it.beforeDue },
        )
        Assert.assertTrue(saved.reminders.all { it.outcome == ReminderOutcome.NotReady })
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("retry_reminders_${item.id}"))
        compose.onNodeWithTag("retry_reminders_${item.id}").assertIsDisplayed()

        scheduler.scheduleSucceeds = true
        compose.onNodeWithTag("retry_reminders_${item.id}").performClick()
        waitFor { vm.state.value.items.single().reminders.all { it.outcome == ReminderOutcome.Scheduled } }
        compose.onNodeWithTag("message").assertTextEquals("已保存并安排提醒")

        open(item)
        detailClick("remove_reminder_1")
        saveDetail()
        val reduced = runBlocking { repo.findById(item.id)!! }
        Assert.assertEquals(2, reduced.reminders.size)
        Assert.assertEquals(1, scheduler.cancelled.count { it == item.id })
        Assert.assertEquals(2, scheduler.pending.size)
    }

    private fun waitFor(condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                // Room resumes viewModelScope on Android's paused main looper, not the Compose clock.
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Exception) {
            throw AssertionError("UI state at timeout: ${vm.state.value}", e)
        }
    }

    private fun openAdd() {
        if (vm.state.value.addOpen) return
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("text_add_button"))
        compose.onNodeWithTag("text_add_button").performClick()
        waitFor { vm.state.value.addOpen }
    }
}
