package com.voicetodo.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.domain.Quadrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QuadrantPreferencesStoreTest {
    @Test fun layoutAndCompletedPreferenceSurviveRecreationAndInvalidOrderFallsBack() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteSharedPreferences(QuadrantPreferencesStore.PREFERENCES_NAME)
        val store = QuadrantPreferencesStore(context)

        assertEquals(Quadrant.entries, store.load().order)
        assertFalse(store.load().showCompleted)

        val reversed = Quadrant.entries.reversed()
        store.saveOrder(reversed)
        store.saveShowCompleted(true)

        val reopened = QuadrantPreferencesStore(context)
        assertEquals(reversed, reopened.load().order)
        assertTrue(reopened.load().showCompleted)

        context.getSharedPreferences(QuadrantPreferencesStore.PREFERENCES_NAME, 0).edit()
            .putString(QuadrantPreferencesStore.KEY_ORDER, "ImportantUrgent,ImportantUrgent")
            .commit()
        assertEquals(Quadrant.entries, reopened.load().order)
        assertTrue(reopened.load().showCompleted)
    }
}
