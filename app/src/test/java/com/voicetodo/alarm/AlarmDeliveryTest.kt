package com.voicetodo.alarm

import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.VoiceTodoApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoEdit
import kotlinx.coroutines.runBlocking
import java.time.Instant
import android.content.ContextWrapper
import android.content.Context
import android.content.ComponentName

/** 验证系统闹钟投递到前台服务后，用户仍有全屏提醒和关闭入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmDeliveryTest {
    @Test
    fun scheduledAlarmKeepsFullScreenAndDismissActionWhenServiceTakesOver() {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val at = Instant.now().plusSeconds(3600)
        val item = runBlocking { app.useCases.confirmDraft("旧标题", at).item }
        runBlocking { app.useCases.updateItem(item.id, TodoEdit("锁屏提醒新标题", null, null, at)) }
        val serviceIntent = Intent(app, RingingService::class.java).apply {
            ReminderToken(item.id, item.reminderRevision, at.toEpochMilli()).putInto(this)
            putExtra(SystemReminderScheduler.EXTRA_TITLE, "旧标题")
        }
        val controller = Robolectric.buildService(RingingService::class.java, serviceIntent).create()
        try {
            controller.startCommand(0, 1)
            waitFor { shadowOf(controller.get()).lastForegroundNotification != null }
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertNotNull("前台通知不能丢失锁屏全屏入口", notification.fullScreenIntent)
            assertEquals(Notification.CATEGORY_ALARM, notification.category)
            assertEquals("关闭提醒", notification.actions.single().title)
            assertEquals("锁屏提醒新标题", notification.extras.getString(Notification.EXTRA_TEXT))
            assertNotNull(runBlocking { app.repository.deliveredAt(item.id, item.reminderRevision) })
            controller.startCommand(0, 2)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(notification, shadowOf(controller.get()).lastForegroundNotification)
            notification.actions.single().actionIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val stoppedService = shadowOf(app).nextStoppedService
            assertEquals(RingingService::class.java.name, stoppedService.component!!.className)
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            assertEquals(0, manager.activeNotifications.size)
        } finally {
            controller.destroy()
            runBlocking { app.useCases.deleteItem(item.id) }
        }
    }

    @Test fun serviceSecondCheckRejectsCompletedDeletedAndOldRevisionBeforeNotification() {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val at = Instant.now().plusSeconds(3600)
        val item = TodoItem(id = "reject", title = "X", remindAt = at, createdAt = Instant.now(), reminderRevision = 2)
        for (current in listOf(item.copy(completedAt = Instant.now()), item, null)) {
            runBlocking {
                if (current == null) app.repository.delete(item.id) else app.repository.save(current)
            }
            val revision = if (current == item) 1L else 2L
            val intent = ReminderToken(item.id, revision, at.toEpochMilli()).putInto(Intent(app, RingingService::class.java))
            val controller = Robolectric.buildService(RingingService::class.java, intent).create()
            try {
                controller.startCommand(0, 1)
                waitFor { shadowOf(controller.get()).isStoppedBySelf }
                assertNull(shadowOf(controller.get()).lastForegroundNotification)
                assertNull(runBlocking { app.repository.deliveredAt(item.id, revision) })
            } finally { controller.destroy() }
        }
    }

    @Test fun receiverDoesNotRecordServiceStartButRecordsSuccessfulFallbackAndNotFailedFallback() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val at = Instant.now().plusSeconds(3600)
        val item = app.useCases.confirmDraft("Current fallback title", at).item
        val intent = ReminderToken(item.id, 0, at.toEpochMilli()).putInto(Intent().apply {
            action = SystemReminderScheduler.ACTION_FIRE
            putExtra(SystemReminderScheduler.EXTRA_TITLE, "stale title")
        })
        var starts = 0
        val accepted = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName {
                starts++
                assertEquals(0L, service.getLongExtra(SystemReminderScheduler.EXTRA_REVISION, -1))
                return ComponentName(app, RingingService::class.java)
            }
        }
        val receiver = AlarmReceiver()
        receiver.deliver(accepted, intent)
        assertEquals(1, starts)
        assertNull(app.repository.deliveredAt(item.id, 0))
        val bothFail = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName = throw SecurityException("FGS denied")
            override fun getSystemService(name: String): Any? {
                if (name == Context.NOTIFICATION_SERVICE) throw SecurityException("notification denied")
                return super.getSystemService(name)
            }
        }
        receiver.deliver(bothFail, intent)
        assertNull(app.repository.deliveredAt(item.id, 0))
        val fallback = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName = throw SecurityException("FGS denied")
        }
        receiver.deliver(fallback, intent)
        assertNotNull(app.repository.deliveredAt(item.id, 0))
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        assertEquals("Current fallback title", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
        receiver.deliver(accepted, intent)
        assertEquals("duplicate receiver must not start another service", 1, starts)
        manager.cancelAll()
        app.useCases.deleteItem(item.id)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("asynchronous delivery did not finish", condition())
    }
}
