package com.voicetodo.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.VoiceTodoApp
import com.voicetodo.domain.ReminderOutcome
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Android 通知入口集成：三种动作都从通知调用，并各自生效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderActionReceiverTest {
    private lateinit var app: VoiceTodoApp
    private lateinit var nm: NotificationManager
    private val cleanup = mutableListOf<String>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        nm = app.getSystemService(NotificationManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun tearDown() {
        nm.cancelAll()
        runBlocking { cleanup.forEach { app.useCases.deleteItem(it) } }
    }

    private suspend fun newItem(title: String): String {
        val at = Instant.now().plusSeconds(3600)
        val id = app.useCases.confirmDraft(title, at).item.id
        cleanup += id
        return id
    }

    private fun actionIntent(action: String) = Intent(app, AlarmActionReceiver::class.java).apply {
        this.action = action
    }

    @Test fun notificationOffersCompleteSnoozeAndDismissActions() = runBlocking {
        val id = newItem("通知三动作")
        val item = app.useCases.findItem(id)
        AlarmReceiver.showFallbackNotification(app, item, item.id)
        val notification = nm.activeNotifications.single().notification
        assertEquals(listOf("完成", "延后", "关闭"),
            notification.actions.map { it.title.toString() })
        assertNotNull("必须保留全屏入口", notification.fullScreenIntent)
    }

    @Test fun completeActionFromReceiverMarksDone() = runBlocking {
        val id = newItem("完成动作")
        val outcome = AlarmActionReceiver().handle(app, AlarmActionReceiver.ACTION_COMPLETE, id, actionIntent(AlarmActionReceiver.ACTION_COMPLETE))
        assertNotNull(outcome)
        assertNotNull(app.useCases.findItem(id).completedAt)
    }

    @Test fun snoozeActionFromReceiverReschedulesAlarm() = runBlocking {
        val id = newItem("延后动作")
        val before = Instant.now()
        val intent = actionIntent(AlarmActionReceiver.ACTION_SNOOZE).putExtra(
            AlarmActionReceiver.EXTRA_SNOOZE_MINUTES, 30L)
        val outcome = AlarmActionReceiver().handle(app, AlarmActionReceiver.ACTION_SNOOZE, id, intent)
        assertEquals(ReminderOutcome.Scheduled, outcome!!.outcome)
        val saved = app.useCases.findItem(id)
        assertNotNull(saved.dismissedAt)
        val delta = java.time.Duration.between(before, saved.remindAt).toMinutes()
        assertTrue("延后应约等于 30 分钟，实际 $delta", delta in 29..31)

        // 延后重新登记的系统闹钟到点必须能真正投递，而不是被当作过期提醒丢弃。
        val serviceIntent = ReminderToken(saved.id, saved.reminderRevision, saved.remindAt!!.toEpochMilli())
            .putInto(Intent(app, RingingService::class.java))
        val controller = org.robolectric.Robolectric.buildService(RingingService::class.java, serviceIntent).create()
        try {
            controller.startCommand(0, 1)
            waitFor { runBlocking { app.repository.deliveredAt(saved.id, saved.id, saved.reminderRevision) } != null }
        } finally {
            controller.destroy()
        }
    }

    @Test fun dismissedReminderIsNotRescheduledOnReconcile() = runBlocking {
        val id = newItem("关闭后不重排")
        AlarmActionReceiver().handle(app, AlarmActionReceiver.ACTION_DISMISS, id,
            actionIntent(AlarmActionReceiver.ACTION_DISMISS))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        app.useCases.reconcileReminders()
        val item = app.useCases.findItem(id)
        // 关闭后该提醒标记为 NotRequested；对账不得把它重新排入系统闹钟。
        assertTrue(item.reminders.all { it.outcome == ReminderOutcome.NotRequested })
    }

    @Test fun dismissActionFromReceiverDoesNotComplete() = runBlocking {
        val id = newItem("关闭动作")
        AlarmActionReceiver().handle(app, AlarmActionReceiver.ACTION_DISMISS, id, actionIntent(AlarmActionReceiver.ACTION_DISMISS))
        val saved = app.useCases.findItem(id)
        assertNull("关闭不完成事项", saved.completedAt)
        assertNotNull(saved.dismissedAt)
    }

    @Test fun stoppingOneReminderKeepsTheOtherSimultaneousNotification() = runBlocking {
        val first = newItem("同刻一")
        val second = newItem("同刻二")
        AlarmReceiver.showFallbackNotification(app, app.useCases.findItem(first), first)
        AlarmReceiver.showFallbackNotification(app, app.useCases.findItem(second), second)
        assertEquals(2, nm.activeNotifications.size)

        AlarmActionReceiver.stopOngoingReminder(app, first, first)

        assertEquals(1, nm.activeNotifications.size)
        assertEquals(AlarmReceiver.notificationIdFor(second, second),
            nm.activeNotifications.single().id)
    }

    @Test fun stopOngoingReminderClearsNotificationAndStopsService() {
        val id = runBlocking { newItem("停止声音") }
        runBlocking { AlarmReceiver.showFallbackNotification(app, app.useCases.findItem(id), id) }
        assertEquals(1, nm.activeNotifications.size)
        AlarmActionReceiver.stopOngoingReminder(app, id, id)
        assertEquals(0, nm.activeNotifications.size)
        val stopped = org.robolectric.Shadows.shadowOf(app).nextStoppedService
        assertEquals(RingingService::class.java.name, stopped.component!!.className)
    }

    @Test fun defaultRingStopsAtSixtySecondsAndKeepsNotification() = runBlocking {
        val id = newItem("持续响铃默认")
        app.useCases.setPersistentRing(id, true)
        val item = app.useCases.findItem(id)
        val intent = ReminderToken(item.id, item.reminderRevision, item.remindAt!!.toEpochMilli())
            .putInto(Intent(app, RingingService::class.java))
        val controller = org.robolectric.Robolectric.buildService(RingingService::class.java, intent).create()
        try {
            controller.startCommand(0, 1)
            val shadow = org.robolectric.Shadows.shadowOf(controller.get())
            val end = System.nanoTime() + 3_000_000_000L
            while (shadow.lastForegroundNotification == null && System.nanoTime() < end) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertNotNull(shadow.lastForegroundNotification)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(RingingService.DEFAULT_RING_MILLIS / 1000 + 1))
            // 到点停止声音后通知仍保留（未被取消）。
            assertEquals(1, nm.activeNotifications.size)
        } finally {
            controller.destroy()
        }
    }

    @Test fun persistentRingOptionIsVisibleOnDetailState() = runBlocking {
        val id = newItem("详情持续响铃")
        app.useCases.setPersistentRing(id, true)
        assertTrue(app.useCases.findItem(id).persistentRing)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("异步投递未完成", condition())
    }
}
