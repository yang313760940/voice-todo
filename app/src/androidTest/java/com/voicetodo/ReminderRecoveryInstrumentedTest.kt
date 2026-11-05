package com.voicetodo

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicetodo.alarm.AlarmReceiver
import com.voicetodo.alarm.BootReceiver
import com.voicetodo.alarm.SystemReminderScheduler
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRecoveryInstrumentedTest {
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
}
