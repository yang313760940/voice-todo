package com.voicetodo

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetodo.alarm.RingingService
import com.voicetodo.alarm.readReminderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderCapabilitiesInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()

    @Test fun targetPermissionsAndMediaPlaybackServiceMatchReminderContract() {
        val packageInfo = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertEquals(34, app.applicationInfo.targetSdkVersion)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in permissions)
        assertTrue(Manifest.permission.USE_FULL_SCREEN_INTENT in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK in permissions)
        val ringingService = packageInfo.services.orEmpty().single {
            it.name == RingingService::class.java.name
        }
        assertTrue(
            ringingService.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0,
        )
    }

    @Test fun currentDeviceCapabilityProbeReturnsACompleteSnapshot() {
        val capabilities = readReminderCapabilities(app)

        assertTrue(capabilities.apiLevel >= 29)
        assertEquals(34, capabilities.targetSdk)
        assertNotNull(capabilities.notificationConditionsReady)
    }
}
