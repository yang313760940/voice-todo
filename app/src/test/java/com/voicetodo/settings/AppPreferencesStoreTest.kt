package com.voicetodo.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppPreferencesStoreTest {
    @Test fun appearanceSurvivesStoreRecreationAndUnknownValuesFallBackSafely() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteSharedPreferences(AppPreferencesStore.PREFERENCES_NAME)
        val store = AppPreferencesStore(context)

        assertEquals(AppPreferences(), store.load())
        store.saveTheme(ThemePreference.Dark)
        store.saveFontSize(FontSizePreference.Large)

        val reopened = AppPreferencesStore(context)
        assertEquals(ThemePreference.Dark, reopened.load().theme)
        assertEquals(FontSizePreference.Large, reopened.load().fontSize)

        context.getSharedPreferences(AppPreferencesStore.PREFERENCES_NAME, 0).edit()
            .putString(AppPreferencesStore.KEY_THEME, "RemovedTheme")
            .putString(AppPreferencesStore.KEY_FONT_SIZE, "RemovedSize")
            .commit()
        assertEquals(AppPreferences(), reopened.load())
    }
}
