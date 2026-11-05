package com.voicetodo.alarm

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.VoiceTodoApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ReminderCapabilityPolicyTest {
    private val allGranted = ReminderCapabilitySignals(
        exactAlarmAccessGranted = true,
        notificationPermissionGranted = true,
        appNotificationsEnabled = true,
        reminderChannelEnabled = true,
        reminderChannelSoundEnabled = true,
        fullScreenPermissionDeclared = true,
        fullScreenAccessGranted = true,
        microphonePermissionGranted = true,
        alarmVolumeAudible = true,
        doNotDisturbActive = false,
    )

    @Test fun api29NeedsNoSpecialExactOrNotificationRuntimeAccess() {
        val capabilities = evaluateReminderCapabilities(
            apiLevel = 29,
            targetSdk = 34,
            signals = allGranted.copy(
                exactAlarmAccessGranted = false,
                notificationPermissionGranted = false,
            ),
        )

        assertFalse(capabilities.exactAlarmAccessRequired)
        assertFalse(capabilities.notificationPermissionRequired)
        assertTrue(capabilities.canScheduleExactAlarm)
        assertTrue(capabilities.canPostNotifications)
        assertTrue(capabilities.canUseFullScreenIntent)
    }

    @Test fun api31RequiresExactAlarmAccessButKeepsNotificationIndependent() {
        val capabilities = evaluateReminderCapabilities(
            apiLevel = 31,
            targetSdk = 34,
            signals = allGranted.copy(exactAlarmAccessGranted = false),
        )

        assertTrue(capabilities.exactAlarmAccessRequired)
        assertFalse(capabilities.canScheduleExactAlarm)
        assertTrue(capabilities.canPostNotifications)
    }

    @Test fun api33NotificationPermissionAndChannelAreBothRequired() {
        val noRuntimePermission = evaluateReminderCapabilities(
            apiLevel = 33,
            targetSdk = 34,
            signals = allGranted.copy(notificationPermissionGranted = false),
        )
        val channelDisabled = evaluateReminderCapabilities(
            apiLevel = 33,
            targetSdk = 34,
            signals = allGranted.copy(reminderChannelEnabled = false),
        )

        assertFalse(noRuntimePermission.canPostNotifications)
        assertFalse(channelDisabled.canPostNotifications)
        assertTrue(noRuntimePermission.canScheduleExactAlarm)
    }

    @Test fun api34FullScreenDenialDoesNotDisableNotificationOrSound() {
        val capabilities = evaluateReminderCapabilities(
            apiLevel = 34,
            targetSdk = 34,
            signals = allGranted.copy(fullScreenAccessGranted = false),
        )

        assertTrue(capabilities.fullScreenAccessManaged)
        assertFalse(capabilities.canUseFullScreenIntent)
        assertTrue(capabilities.canPostNotifications)
        assertTrue(capabilities.soundConditionsReady)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReminderCapabilityProbeTest {
    @Test fun readingCapabilitiesUsesGuardedPlatformCalls() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        VoiceTodoApp.createNotificationChannels(app)
        val capabilities = readReminderCapabilities(app)

        assertEquals(Build.VERSION.SDK_INT, capabilities.apiLevel)
        assertEquals(34, capabilities.targetSdk)
        assertEquals(Build.VERSION.SDK_INT >= 31, capabilities.exactAlarmAccessRequired)
        assertEquals(Build.VERSION.SDK_INT >= 33, capabilities.notificationPermissionRequired)
        assertEquals(Build.VERSION.SDK_INT >= 34, capabilities.fullScreenAccessManaged)
    }
}
