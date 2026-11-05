package com.voicetodo.alarm

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DeviceProtectedReminderStoreTest {
    private lateinit var app: Application
    private lateinit var store: DeviceProtectedReminderStore

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val deviceContext = app.createDeviceProtectedStorageContext()
        deviceContext.getSharedPreferences(DeviceProtectedReminderStore.PREFERENCES, 0)
            .edit().clear().commit()
        app.getSystemService(NotificationManager::class.java).cancelAll()
        store = DeviceProtectedReminderStore(app)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test fun mirrorContainsOnlySchedulingIdentityAndNeverStoresPrivateContent() {
        val at = Instant.parse("2030-01-01T00:00:00Z")
        val token = ReminderToken("item-1", 7, at.toEpochMilli(), "reminder-1")

        assertTrue(SystemReminderScheduler(app).scheduleReminder(
            token.itemId,
            "给妈妈买药 sk-secret",
            token.reminderId,
            at,
            token.revision,
        ))

        val raw = app.createDeviceProtectedStorageContext()
            .getSharedPreferences(DeviceProtectedReminderStore.PREFERENCES, 0)
            .getString(DeviceProtectedReminderStore.KEY_RECORDS, null).orEmpty()
        assertTrue(raw.contains("item-1"))
        assertTrue(raw.contains("reminder-1"))
        assertFalse(raw.contains("给妈妈买药"))
        assertFalse(raw.contains("sk-secret"))
        assertEquals(token, store.records().single().token)
    }

    @Test fun beforeUnlockDeliveryIsGenericAndCannotBePublishedTwice() {
        val token = ReminderToken("private-item", 3, 1_800_000L, "private-reminder")
        store.upsert(token)

        assertTrue(AlarmReceiver.deliverBeforeUnlock(app, token))
        assertFalse(AlarmReceiver.deliverBeforeUnlock(app, token))

        val notification = app.getSystemService(NotificationManager::class.java)
            .activeNotifications.single().notification
        assertEquals("解锁后查看事项", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertTrue(notification.actions.isNullOrEmpty())
        assertNull(notification.contentIntent)
        assertNull(notification.fullScreenIntent)
        assertEquals(1, store.delivered().size)
    }

    @Test fun newRevisionRearmsDeliveredIdentityButPruningRemovesCompletedOrCancelledOnes() {
        val first = ReminderToken("item", 1, 1_800_000L, "reminder")
        store.upsert(first)
        assertTrue(store.deliverIfCurrent(first, Instant.ofEpochMilli(1_800_000L)) { true })

        val second = first.copy(revision = 2, atMillis = 3_600_000L)
        store.upsert(second)
        assertTrue(store.delivered().isEmpty())
        assertEquals(second, store.pending(Instant.EPOCH).single().token)

        store.retainValid(emptySet())
        assertTrue(store.records().isEmpty())
    }
}
