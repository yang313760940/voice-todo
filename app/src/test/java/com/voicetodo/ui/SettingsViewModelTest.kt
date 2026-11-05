package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.domain.Clock
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FocusUseCases
import com.voicetodo.domain.InMemoryFocusRepository
import com.voicetodo.domain.RecordedFocusScheduler
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.settings.AppPreferencesStore
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SettingsViewModelTest {
    @Test fun appearanceAndPomodoroChangesPersistImmediately() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteSharedPreferences(AppPreferencesStore.PREFERENCES_NAME)
        val store = AppPreferencesStore(app)
        val repository = InMemoryFocusRepository()
        val focus = FocusUseCases(
            repository = repository,
            scheduler = RecordedFocusScheduler(),
            clock = object : Clock {
                override fun now(): Instant = Instant.parse("2026-11-05T01:00:00Z")
            },
        )
        val vm = SettingsViewModel(
            app = app,
            preferencesStore = store,
            focusUseCases = focus,
            capabilitiesProvider = {
                ReminderCapabilities(
                    canScheduleExactAlarm = true,
                    canPostNotifications = false,
                    canRecordAudio = true,
                )
            },
        )

        vm.setTheme(ThemePreference.Dark)
        vm.setFontSize(FontSizePreference.Large)
        vm.updateFocusSettings(FocusSettings(30, 6, 18, 3))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(ThemePreference.Dark, store.load().theme)
        assertEquals(FontSizePreference.Large, store.load().fontSize)
        assertEquals(FocusSettings(30, 6, 18, 3), runBlocking { repository.settings() })
        assertEquals(false, vm.state.value.capabilities?.canPostNotifications)
    }

    @Test fun trashRefreshAndRestoreUpdateSettingsStateAndTodoData() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteSharedPreferences(AppPreferencesStore.PREFERENCES_NAME)
        val db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        try {
            val zone = ZoneId.of("Asia/Shanghai")
            val now = Instant.parse("2026-11-05T01:00:00Z")
            val clock = FrozenClock(now)
            val repo = RoomTodoRepository(db, FileTodoRepository(
                java.nio.file.Files.createTempDirectory("settings-trash").toFile(), zone))
            val useCases = TodoUseCases(repo, RecordedScheduler(), clock, zone)
            val item = runBlocking {
                useCases.confirmDraft("待恢复", now.plusSeconds(3600)).item.also {
                    useCases.deleteItem(it.id)
                }
            }
            val vm = SettingsViewModel(
                app = app,
                preferencesStore = AppPreferencesStore(app),
                focusUseCases = FocusUseCases(
                    InMemoryFocusRepository(),
                    RecordedFocusScheduler(),
                    clock,
                ),
                capabilitiesProvider = { ReminderCapabilities(true, true, true) },
                todoUseCases = useCases,
            )

            vm.refreshTrash()
            waitUntil { vm.state.value.trash.items.isNotEmpty() }
            assertEquals(listOf(item.id), vm.state.value.trash.items.map { it.item.id })

            vm.restoreTrash(item.id)
            waitUntil { vm.state.value.trash.changeGeneration == 1L }
            assertEquals(emptyList<Any>(), vm.state.value.trash.items)
            assertEquals(1, vm.state.value.trash.changeGeneration)
            assertEquals(1, runBlocking { useCases.listItems().size })
        } finally {
            db.close()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met before timeout")
    }
}
