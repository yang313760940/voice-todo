package com.voicetodo.alarm

import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.VoiceTodoApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** 验证系统闹钟投递到前台服务后，用户仍有全屏提醒和关闭入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmDeliveryTest {
    @Test
    fun scheduledAlarmKeepsFullScreenAndDismissActionWhenServiceTakesOver() {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        app.scheduler.schedule("locked-reminder", "锁屏提醒", LocalDateTime.now().plusMinutes(1))

        shadowOf(alarms).nextScheduledAlarm.operation.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val serviceIntent = shadowOf(app).nextStartedService
        assertNotNull("到点必须启动响铃服务", serviceIntent)
        val controller = Robolectric.buildService(RingingService::class.java, serviceIntent).create()
        try {
            controller.startCommand(0, 1)
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertNotNull("前台通知不能丢失锁屏全屏入口", notification.fullScreenIntent)
            assertEquals(Notification.CATEGORY_ALARM, notification.category)
            assertEquals("关闭提醒", notification.actions.single().title)
            notification.actions.single().actionIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val stoppedService = shadowOf(app).nextStoppedService
            assertEquals(RingingService::class.java.name, stoppedService.component!!.className)
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            assertEquals(0, manager.activeNotifications.size)
        } finally {
            controller.destroy()
        }
    }
}
