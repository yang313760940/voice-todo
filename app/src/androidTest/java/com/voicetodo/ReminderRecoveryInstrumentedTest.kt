package com.voicetodo

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicetodo.alarm.AlarmReceiver
import com.voicetodo.alarm.BootReceiver
import com.voicetodo.alarm.DeviceProtectedReminderStore
import com.voicetodo.alarm.ReminderToken
import com.voicetodo.alarm.SystemReminderScheduler
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRecoveryInstrumentedTest {
    @Test fun beforeFirstUnlockUsesOnlyDeviceProtectedMirrorAndGenericNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantNotificationPermission(context)
        val store = DeviceProtectedReminderStore(context)
        val token = ReminderToken(
            "direct-boot-${System.nanoTime()}",
            4,
            Instant.now().plusSeconds(30 * 60).toEpochMilli(),
            "generic-reminder",
        )
        store.upsert(token)
        try {
            assertTrue(AlarmReceiver.deliverBeforeUnlock(context, token))
            assertFalse(AlarmReceiver.deliverBeforeUnlock(context, token))
            val notification = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.single { it.id == AlarmReceiver.notificationIdFor(token.itemId, token.reminderId) }
                .notification
            assertEquals("解锁后查看事项", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
            assertTrue(notification.actions.isNullOrEmpty())
        } finally {
            context.getSystemService(NotificationManager::class.java)
                .cancel(AlarmReceiver.notificationIdFor(token.itemId, token.reminderId))
            store.removeItem(token.itemId)
        }
    }

    @Test fun afterFirstUnlockReconcilesMainDataWithoutRevivingCompletedReminder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as VoiceTodoApp
        val item = app.useCases.confirmDraft(
            title = "unlock reconcile ${System.nanoTime()}",
            remindAt = Instant.now().plusSeconds(30 * 60),
        ).item
        try {
            app.useCases.completeItem(item.id)
            app.reconcileAfterUnlock()

            assertFalse(app.directBootStore.records().any { it.itemId == item.id })
            assertTrue(pendingIntent(context, item.id) == null)
        } finally {
            app.useCases.deleteItem(item.id)
        }
    }

    @Test fun afterFirstUnlockImportsPreUnlockDeliveryWithoutRingingAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantNotificationPermission(context)
        val app = context.applicationContext as VoiceTodoApp
        val item = app.useCases.confirmDraft(
            title = "private unlock ${System.nanoTime()}",
            remindAt = Instant.now().plusSeconds(30 * 60),
        ).item
        val token = ReminderToken(
            item.id,
            item.reminderRevision,
            requireNotNull(item.remindAt).toEpochMilli(),
            item.id,
        )
        try {
            app.directBootStore.upsert(token)
            assertTrue(AlarmReceiver.deliverBeforeUnlock(context, token))

            app.reconcileAfterUnlock()

            assertTrue(app.useCases.wasDelivered(app.useCases.findItem(item.id)))
            assertFalse(app.directBootStore.records().any { it.itemId == item.id })
            assertTrue(pendingIntent(context, item.id) == null)
            val notification = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.single { it.id == AlarmReceiver.notificationIdFor(item.id) }
                .notification
            assertEquals(item.title, notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(listOf("完成", "延后", "关闭"), notification.actions.map { it.title.toString() })
            assertTrue(notification.fullScreenIntent == null)
        } finally {
            context.getSystemService(NotificationManager::class.java)
                .cancel(AlarmReceiver.notificationIdFor(item.id))
            app.useCases.deleteItem(item.id)
        }
    }

    @Test fun realAlarmManagerRestoresPersistedReminderAndRecoveryReceiverIsRegistered() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as VoiceTodoApp
        assumeTrue("设备需允许精确闹钟才能验证恢复", app.scheduler.capabilities().canScheduleExactAlarm)
        val item = app.useCases.confirmDraft(
            title = "instrumented recovery ${System.nanoTime()}",
            remindAt = Instant.now().plusSeconds(30 * 60),
        ).item
        try {
            app.scheduler.cancel(item.id)
            app.reminderRecovery.reconcile()

            assertNotNull(pendingIntent(context, item.id))
            val component = ComponentName(context, BootReceiver::class.java)
            assertNotNull(context.packageManager.getReceiverInfo(component, 0))
            val actions = listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            )
            actions.forEach { action ->
                val receivers = context.packageManager.queryBroadcastReceivers(
                    Intent(action).setPackage(context.packageName),
                    0,
                )
                assertTrue("缺少恢复广播：$action", receivers.any {
                    it.activityInfo.name == BootReceiver::class.java.name
                })
            }
            val info = context.packageManager.getReceiverInfo(component, 0)
            assertTrue("恢复接收器必须允许 Direct Boot", info.directBootAware)
            val alarmInfo = context.packageManager.getReceiverInfo(
                ComponentName(context, AlarmReceiver::class.java),
                0,
            )
            assertTrue("到点接收器必须允许 Direct Boot", alarmInfo.directBootAware)
        } finally {
            app.useCases.deleteItem(item.id)
        }
    }

    private fun pendingIntent(context: android.content.Context, itemId: String): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = SystemReminderScheduler.ACTION_FIRE
            data = Uri.Builder().scheme("voicetodo").authority("alarm").appendPath(itemId).build()
        }
        return PendingIntent.getBroadcast(
            context,
            SystemReminderScheduler.requestCodeFor(itemId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun grantNotificationPermission(context: android.content.Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${android.Manifest.permission.POST_NOTIFICATIONS}",
        )
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
