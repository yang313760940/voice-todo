package com.voicetodo.alarm

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.VoiceTodoApp
import com.voicetodo.domain.TodoItem
import com.voicetodo.speech.ReminderSpeaker
import com.voicetodo.speech.SpeechAvailability
import com.voicetodo.speech.SpeechFallbackReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant

/**
 * 到点朗读的真实服务/通知适配：成功、锁屏隐私、资源回退与停止。
 * 语音引擎用假边界替代，不依赖设备是否安装中文语音包。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderSpeechDeliveryTest {
    private lateinit var app: VoiceTodoApp
    private lateinit var nm: NotificationManager
    private lateinit var fake: FakeSpeaker
    private val cleanup = mutableListOf<String>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        nm = app.getSystemService(NotificationManager::class.java)
        fake = FakeSpeaker()
        RingingService.speakerFactory = { fake }
    }

    @After fun tearDown() {
        RingingService.speakerFactory = { com.voicetodo.speech.AndroidReminderSpeaker(it) }
        app.getSystemService(KeyguardManager::class.java).let { shadowOf(it).setKeyguardLocked(false) }
        nm.cancelAll()
        runBlocking { cleanup.forEach { app.useCases.deleteItem(it) } }
    }

    @Test fun enabledReadingSpeaksTitleOnceAndKeepsPrivateNotification() = runBlocking {
        val item = newItem("朗读成功")
        val controller = start(item)
        try {
            waitFor { fake.spoken.isNotEmpty() }
            assertEquals(listOf("朗读成功"), fake.spoken)
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertEquals("朗读成功", notification.extras.getString(Notification.EXTRA_TEXT))
            assertNull(notification.extras.getString(Notification.EXTRA_SUB_TEXT))
            assertEquals(
                "解锁后查看事项",
                notification.publicVersion.extras.getString(Notification.EXTRA_TEXT),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test fun lockedDeviceDoesNotSpeakTitleUnlessExplicitlyAllowed() = runBlocking {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)

        val blocked = newItem("锁屏隐私")
        val first = start(blocked)
        try {
            waitFor { shadowOf(first.get()).lastForegroundNotification != null }
            assertTrue("锁屏默认不能朗读正文", fake.spoken.isEmpty())
        } finally {
            first.destroy()
        }

        val allowed = newItem("锁屏允许", readAloudWhenLocked = true)
        val second = start(allowed)
        try {
            waitFor { fake.spoken.contains("锁屏允许") }
        } finally {
            second.destroy()
        }
    }

    @Test fun missingLocalResourceFallsBackToToneAndShowsReason() = runBlocking {
        fake.availability = SpeechAvailability.MissingLanguageData
        val item = newItem("缺资源")
        val controller = start(item)
        try {
            waitFor { shadowOf(controller.get()).lastForegroundNotification != null }
            assertTrue(fake.spoken.isEmpty())
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertEquals(
                SpeechFallbackReason.MISSING_DATA,
                notification.extras.getString(Notification.EXTRA_SUB_TEXT),
            )
            assertEquals(
                "解锁后查看事项",
                notification.publicVersion.extras.getString(Notification.EXTRA_TEXT),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test fun closingReminderStopsSpeechAndDuplicateDeliveryDoesNotRestartIt() = runBlocking {
        val item = newItem("停止朗读")
        val controller = start(item)
        try {
            waitFor { fake.spoken.isNotEmpty() }
            AlarmActionReceiver.stopOngoingReminder(app, item.id, item.id)
            assertEquals(
                RingingService::class.java.name,
                shadowOf(app).nextStoppedService.component!!.className,
            )
        } finally {
            controller.destroy()
        }
        assertTrue("关闭提醒必须停止语音", fake.stopped > 0 || fake.released > 0)

        val spokenBefore = fake.spoken.size
        val duplicate = start(item)
        try {
            waitFor { shadowOf(duplicate.get()).isStoppedBySelf }
            assertEquals("重复投递不得再次朗读", spokenBefore, fake.spoken.size)
        } finally {
            duplicate.destroy()
        }
    }

    @Test fun closingSpeakingReminderStopsItsSpeechWhileAnotherNotificationRemains() = runBlocking {
        val speaking = newItem("正在朗读")
        val other = newItem("另一条提醒")
        val controller = start(speaking)
        try {
            waitFor { fake.spoken.isNotEmpty() }
            AlarmReceiver.showFallbackNotification(app, app.useCases.findItem(other.id), other.id)
            assertEquals(2, nm.activeNotifications.size)

            AlarmActionReceiver.stopOngoingReminder(app, speaking.id, speaking.id)

            assertTrue("关闭当前朗读必须停止声音", fake.stopped > 0)
            assertEquals("另一条提醒的通知应保留", 1, nm.activeNotifications.size)
            assertEquals(
                AlarmReceiver.notificationIdFor(other.id, other.id),
                nm.activeNotifications.single().id,
            )
            assertNull("仍有其它提醒时不应停掉整个前台服务", shadowOf(app).nextStoppedService)
        } finally {
            controller.destroy()
        }
    }

    private suspend fun newItem(
        title: String,
        readAloudWhenLocked: Boolean = false,
    ): TodoItem {
        val at = Instant.now().plusSeconds(3600)
        val id = app.useCases.confirmDraft(title, at).item.id
        cleanup += id
        app.useCases.setReadAloud(id, true)
        if (readAloudWhenLocked) app.useCases.setReadAloudWhenLocked(id, true)
        return app.useCases.findItem(id)
    }

    private fun start(item: TodoItem): ServiceController<RingingService> {
        val intent = ReminderToken(item.id, item.reminderRevision, item.remindAt!!.toEpochMilli())
            .putInto(Intent(app, RingingService::class.java))
        return Robolectric.buildService(RingingService::class.java, intent).create().also {
            it.startCommand(0, 1)
            waitFor {
                shadowOf(it.get()).lastForegroundNotification != null ||
                    shadowOf(it.get()).isStoppedBySelf
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("异步提醒投递未完成", condition())
    }

    private class FakeSpeaker(
        var availability: SpeechAvailability = SpeechAvailability.Ready,
    ) : ReminderSpeaker {
        val spoken = mutableListOf<String>()
        var stopped = 0
        var released = 0
        var completion: (() -> Unit)? = null

        override suspend fun prepare(): SpeechAvailability = availability

        override suspend fun speak(text: String, onCompleted: () -> Unit): Boolean {
            spoken += text
            completion = onCompleted
            return true
        }

        override fun stop() {
            stopped++
        }

        override fun release() {
            released++
        }
    }
}
