package com.voicetodo.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.domain.Clock
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FocusUseCases
import com.voicetodo.domain.InMemoryFocusRepository
import com.voicetodo.domain.RecordedFocusScheduler
import com.voicetodo.settings.AppPreferencesStore
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference
import java.time.Instant
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
}
