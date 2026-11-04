package com.voicetodo.alarm

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.VoiceTodoApp
import com.voicetodo.domain.ReminderEdit
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.TodoEdit
import java.time.Instant
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MultipleAlarmIntegrationTest {
    @Test fun realAlarmAdapterRegistersAndCancelsIndependentPendingIntentsForTheSameInstant() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val alarms = app.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val item = app.useCases.confirmDraft("两个独立系统闹钟").item
        val at = Instant.now().plusSeconds(3600)
        try {
            val edit = TodoEdit(item.title, null, null, null, listOf(
                ReminderEdit("first", at), ReminderEdit("second", at),
            ))
            assertEquals(ReminderOutcome.Scheduled, app.useCases.updateItem(item.id, edit).outcome)
            fun registered() = shadowOf(alarms).scheduledAlarms.filter {
                shadowOf(it.operation).savedIntent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) == item.id
            }
            assertEquals(setOf("first", "second"), registered().map {
                shadowOf(it.operation).savedIntent.getStringExtra("extra_reminder_id")
            }.toSet())
            assertEquals(2, registered().map { it.operation }.distinct().size)
            app.useCases.updateItem(item.id, edit.copy(reminders = listOf(ReminderEdit("second", at))))
            assertEquals(listOf("second"), registered().map {
                shadowOf(it.operation).savedIntent.getStringExtra("extra_reminder_id")
            })
            app.useCases.completeItem(item.id)
            assertTrue(registered().isEmpty())
        } finally { app.useCases.deleteItem(item.id) }
    }

    @Test fun receiverKeepsIndependentReminderIdentityThroughFallbackDelivery() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val at = Instant.now().plusSeconds(3600)
        val item = app.useCases.confirmDraft("同刻投递").item
        try {
            val saved = app.useCases.updateItem(item.id, TodoEdit(item.title, null, null, null, listOf(
                ReminderEdit("first", at), ReminderEdit("second", at),
            ))).item
            val fallback = object : ContextWrapper(app) {
                override fun startForegroundService(service: Intent): ComponentName =
                    throw SecurityException("force notification fallback")
            }
            val receiver = AlarmReceiver()
            for (reminder in saved.reminders) {
                val intent = ReminderToken(item.id, reminder.revision, at.toEpochMilli(), reminder.id)
                    .putInto(Intent().setAction(SystemReminderScheduler.ACTION_FIRE))
                receiver.deliver(fallback, intent)
                receiver.deliver(fallback, intent)
            }
            assertNotNull(app.repository.deliveredAt(item.id, "first", saved.reminders[0].revision))
            assertNotNull(app.repository.deliveredAt(item.id, "second", saved.reminders[1].revision))
            val active = app.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            assertEquals(2, active.size)
            assertEquals(2, active.map { it.id }.distinct().size)
        } finally {
            app.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            app.useCases.deleteItem(item.id)
        }
    }

    @Test fun ringingServicePublishesBothSimultaneousReminderCallbacks() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val at = Instant.now().plusSeconds(3600)
        val item = app.useCases.confirmDraft("同服务双提醒").item
        val saved = app.useCases.updateItem(item.id, TodoEdit(item.title, null, null, null, listOf(
            ReminderEdit("first", at), ReminderEdit("second", at),
        ))).item
        fun serviceIntent(index: Int) = saved.reminders[index].let { reminder ->
            ReminderToken(item.id, reminder.revision, at.toEpochMilli(), reminder.id)
                .putInto(Intent(app, RingingService::class.java))
        }
        val controller = Robolectric.buildService(RingingService::class.java, serviceIntent(0)).create()
        try {
            controller.startCommand(0, 1)
            controller.withIntent(serviceIntent(1)).startCommand(0, 2)
            val end = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < end && saved.reminders.any {
                    app.repository.deliveredAt(item.id, it.id, it.revision) == null
                }) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue(saved.reminders.all {
                app.repository.deliveredAt(item.id, it.id, it.revision) != null
            })
        } finally {
            controller.destroy()
            app.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            app.useCases.deleteItem(item.id)
        }
    }
}
