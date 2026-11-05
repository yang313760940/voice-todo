package com.voicetodo.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.domain.BackupData
import com.voicetodo.domain.BackupRejectedException
import com.voicetodo.domain.BackupStore
import com.voicetodo.domain.DataBackupUseCases
import com.voicetodo.domain.FocusPhase
import com.voicetodo.domain.FocusSession
import com.voicetodo.domain.FocusSessionState
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.RecordedFocusScheduler
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.settings.AppPreferences
import com.voicetodo.settings.AppPreferencesStore
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DataBackupEndToEndTest {
    @get:Rule val temp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = Instant.parse("2026-11-05T01:00:00Z")
    private val clock = FrozenClock(start)
    private val scheduler = RecordedScheduler()
    private val focusScheduler = RecordedFocusScheduler()

    private lateinit var app: Application
    private lateinit var db: TodoDatabase
    private lateinit var repo: RoomTodoRepository
    private lateinit var focusRepo: RoomFocusRepository
    private lateinit var useCases: TodoUseCases
    private lateinit var preferences: AppPreferencesStore
    private lateinit var backupsDir: File
    private lateinit var backup: DataBackupUseCases

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteSharedPreferences(AppPreferencesStore.PREFERENCES_NAME)
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        repo = RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), zone))
        focusRepo = RoomFocusRepository(db)
        useCases = TodoUseCases(repo, scheduler, clock, zone)
        preferences = AppPreferencesStore(app)
        backupsDir = temp.newFolder("backups")
        backup = DataBackupUseCases(
            store = RoomBackupStore(db, repo),
            codec = JsonBackupCodec,
            files = PrivateBackupFileStore(backupsDir),
            scheduler = scheduler,
            focusScheduler = focusScheduler,
            loadPreferences = { preferences.load() },
            savePreferences = { preferences.save(it) },
            clock = clock,
            zone = { zone },
            appVersion = "0.1.0-test",
            reconcile = { useCases.reconcileReminders(currentZone = zone) },
        )
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun exportAndPreviewCarryBusinessDataButNoSecretsOrRecordings() = runTest {
        preferences.save(AppPreferences(ThemePreference.Dark, FontSizePreference.Large))
        val work = useCases.createList("工作")
        useCases.confirmDraft("写周报", remindAt = start.plusSeconds(3600), listId = work.id)
        useCases.confirmDraft("已完成", remindAt = start.plusSeconds(7200)).also {
            useCases.completeItem(it.item.id)
        }
        val futureRule = RecurrenceRule(
            frequency = RecurrenceFrequency.Daily,
            startDate = LocalDate.parse("2026-11-05"),
            reminderTimes = listOf(LocalTime.parse("10:00")),
        )
        useCases.createRecurring("每日阅读", futureRule, work.id)
        focusRepo.save(
            FocusSession(
                id = "focus-live",
                phase = FocusPhase.Focus,
                startedAt = start.minusSeconds(600),
                endsAt = start.plusSeconds(900),
                lastResumedAt = start.minusSeconds(600),
                durationSeconds = 1500,
                itemId = "no-such-item",
            ),
        )
        app.getSharedPreferences("ai_settings", Application.MODE_PRIVATE)
            .edit().putString("encrypted_settings_payload", "sk-very-secret").commit()

        val export = backup.export()
        val document = JsonBackupCodec.decode(export.json)
        val preview = backup.preview(export.json)

        assertEquals(1, preview.formatVersion)
        assertEquals("0.1.0-test", preview.appVersion)
        assertEquals(preview.exportedAt, start)
        assertEquals(1, preview.counts.listCount)
        assertEquals(1, preview.counts.recurringSeriesCount)
        assertEquals(document.data.items.size, preview.counts.itemCount)
        assertEquals(1, preview.counts.focusSessionCount)
        assertEquals(ThemePreference.Dark, document.data.preferences.theme)
        assertEquals(FontSizePreference.Large, document.data.preferences.fontSize)
        assertEquals("focus-live", document.data.focusSessions.single().id)
        assertEquals(FocusSessionState.Abandoned, document.data.focusSessions.single().state)
        assertEquals(600, document.data.focusSessions.single().accumulatedFocusSeconds)
        assertFalse(export.json.contains("sk-very-secret"))
        assertFalse(export.json.contains("apiKey"))
        assertFalse(export.json.contains("recording", ignoreCase = true))
        assertFalse(backupsDir.listFiles().orEmpty().any { it.name.endsWith(".json") })
    }

    @Test fun replaceCreatesRestoreCopyCancelsOldSchedulesAndRebuildsOnlyFutureReminders() = runTest {
        preferences.save(AppPreferences(ThemePreference.Dark, FontSizePreference.Large))
        val sourceFuture = useCases.confirmDraft(
            "未来提醒",
            remindAt = start.plusSeconds(5 * 3600),
        ).item
        val sourcePast = useCases.confirmDraft(
            "稍后会过期",
            remindAt = start.plusSeconds(3600),
        ).item
        val sourceCompleted = useCases.confirmDraft(
            "已完成不排提醒",
            remindAt = start.plusSeconds(2 * 3600),
        ).item
        useCases.completeItem(sourceCompleted.id)
        val exported = backup.export()

        useCases.listItems().forEach { useCases.deleteItem(it.id) }
        val current = useCases.confirmDraft(
            "当前机器数据",
            remindAt = start.plusSeconds(3 * 3600),
            itemId = "current-only",
        ).item
        preferences.save(AppPreferences(ThemePreference.Light, FontSizePreference.Small))
        scheduler.cancelled.clear()
        scheduler.scheduled.clear()
        clock.instant = start.plusSeconds(3 * 3600)

        val outcome = backup.replace(exported.json)

        assertTrue(outcome.remindersRebuilt)
        assertNull(outcome.warning)
        assertTrue(scheduler.cancelled.contains("current-only"))
        assertTrue(outcome.restoreCopyName.startsWith("restore-"))
        val restoreFile = File(backupsDir, outcome.restoreCopyName)
        assertTrue(restoreFile.isFile)
        val restoreJson = restoreFile.readText()
        assertTrue(restoreJson.contains("当前机器数据"))
        assertEquals(
            listOf("未来提醒", "稍后会过期", "已完成不排提醒").sorted(),
            useCases.listItems().map { it.title }.sorted(),
        )
        assertNull(useCases.listItems().firstOrNull { it.id == current.id })

        assertTrue(scheduler.scheduled.any { it.itemId == sourceFuture.id && it.at.isAfter(clock.now()) })
        assertFalse(scheduler.scheduled.any { it.itemId == sourcePast.id })
        assertFalse(scheduler.scheduled.any { it.itemId == sourceCompleted.id })
        assertTrue(scheduler.pending.keys.none { it.first == current.id })
        assertEquals(ThemePreference.Dark, preferences.load().theme)
        assertEquals(FontSizePreference.Large, preferences.load().fontSize)
        assertEquals(1, backup.restoreCopies().size)
    }

    @Test fun corruptedOrIncompatibleBackupIsRejectedWithoutChangingData() = runTest {
        val item = useCases.confirmDraft("原数据", remindAt = start.plusSeconds(3600)).item
        val valid = backup.export()

        val invalidJson = runCatching { backup.replace("{not-json") }.exceptionOrNull()
        assertTrue(invalidJson is BackupRejectedException)
        assertEquals(item.id, useCases.listItems().single().id)
        assertTrue(backupsDir.listFiles().orEmpty().isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())

        val futureVersion = JSONObject(valid.json).put("formatVersion", 99).toString()
        val incompatible = runCatching { backup.replace(futureVersion) }.exceptionOrNull()
        assertTrue(incompatible is BackupRejectedException)
        assertEquals(item.id, useCases.listItems().single().id)
        assertTrue(backupsDir.listFiles().orEmpty().isEmpty())

        val brokenReference = JSONObject(valid.json).apply {
            getJSONObject("data").getJSONArray("items").getJSONObject(0).put("listId", "missing-list")
        }.toString()
        val rejectedReference = runCatching { backup.replace(brokenReference) }.exceptionOrNull()
        assertTrue(rejectedReference is BackupRejectedException)
        assertEquals(item.id, useCases.listItems().single().id)
        assertTrue(backupsDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun restoreCopyCanBePreviewedAndImported() = runTest {
        useCases.confirmDraft("原始数据", remindAt = start.plusSeconds(3600), itemId = "original")
        val original = backup.export()

        useCases.listItems().forEach { useCases.deleteItem(it.id) }
        useCases.confirmDraft("临时数据", itemId = "temporary")
        backup.replace(original.json)
        assertEquals("原始数据", useCases.listItems().single().title)

        val copy = backup.restoreCopies().single()
        val preview = backup.previewRestoreCopy(copy.name)
        assertEquals(1, preview.counts.itemCount)
        assertNotNull(backup.previewRestoreCopy(copy.name))

        backup.replaceRestoreCopy(copy.name)
        assertEquals("临时数据", useCases.listItems().single().title)
        assertEquals(2, backup.restoreCopies().size)
    }

    @Test fun runningSessionInsideABackupNeverResumesAfterImport() = runTest {
        val data = BackupData(
            items = emptyList(),
            lists = emptyList(),
            focusSessions = listOf(
                FocusSession(
                    id = "running",
                    phase = FocusPhase.Focus,
                    startedAt = start.minusSeconds(600),
                    endsAt = start.plusSeconds(900),
                    lastResumedAt = start.minusSeconds(600),
                    durationSeconds = 1500,
                ),
            ),
            focusSettings = FocusSettings(),
            preferences = AppPreferences(),
        )
        val json = JsonBackupCodec.encode(data, start, "0.1.0-test")

        backup.replace(json)

        val restored = db.focus().history().single().session()
        assertEquals(FocusSessionState.Abandoned, restored.state)
        assertEquals(600, restored.accumulatedFocusSeconds)
        assertEquals(0, restored.completedPomodoros)
        assertTrue(focusScheduler.scheduled.isEmpty())
    }

    @Test fun storageFailureLeavesCurrentDataPreferencesAndSchedulesIntact() = runTest {
        preferences.save(AppPreferences(ThemePreference.Dark, FontSizePreference.Large))
        val current = useCases.confirmDraft("原数据", remindAt = start.plusSeconds(3600)).item
        val source = backup.export()
        val failing = DataBackupUseCases(
            store = object : BackupStore {
                private val delegate = RoomBackupStore(db, repo)
                override suspend fun snapshot(preferences: AppPreferences) =
                    delegate.snapshot(preferences)
                override suspend fun replace(data: BackupData) {
                    throw IllegalStateException("模拟磁盘替换失败")
                }
            },
            codec = JsonBackupCodec,
            files = PrivateBackupFileStore(backupsDir),
            scheduler = scheduler,
            focusScheduler = focusScheduler,
            loadPreferences = { preferences.load() },
            savePreferences = { preferences.save(it) },
            clock = clock,
            zone = { zone },
            appVersion = "0.1.0-test",
            reconcile = { useCases.reconcileReminders(currentZone = zone) },
        )
        val importJson = JSONObject(source.json).apply {
            getJSONObject("data").getJSONObject("preferences").put("theme", "Light")
            getJSONObject("data").getJSONObject("preferences").put("fontSize", "Small")
            getJSONObject("data").getJSONArray("items").getJSONObject(0).put("title", "新数据")
        }.toString()
        scheduler.scheduled.clear()

        val failure = runCatching { failing.replace(importJson) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(current.id, useCases.listItems().single().id)
        assertEquals("原数据", useCases.listItems().single().title)
        assertEquals(ThemePreference.Dark, preferences.load().theme)
        assertEquals(FontSizePreference.Large, preferences.load().fontSize)
        assertTrue(scheduler.scheduled.any { it.itemId == current.id })
        assertEquals(1, restoreCopyCount())
    }

    private fun restoreCopyCount(): Int =
        backupsDir.listFiles().orEmpty().count { it.name.startsWith("restore-") }
}
