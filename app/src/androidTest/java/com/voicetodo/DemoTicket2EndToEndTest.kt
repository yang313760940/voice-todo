package com.voicetodo

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetodo.alarm.AlarmReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * 端到端演示（冷启动路径）：保存一条约 40 秒后到点的事项 →
 * 等待系统闹钟真实投递（AlarmReceiver 发出的通知可见）。用于产出可复现证据。
 */
@RunWith(AndroidJUnit4::class)
class DemoTicket2EndToEndTest {

    private val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
    private val ctx: Context = app

    @Test
    fun saveAndAlarmFires() {
        val text = "端到端演示事项"

        // 保存一条 40 秒后到点的真实系统闹钟事项
        val at = Instant.now().plusSeconds(40)
        val saved = runBlocking { app.useCases.confirmDraft(text, at) }
        assertTrue("应已安排系统闹钟", saved.outcome == com.voicetodo.domain.ReminderOutcome.Scheduled)
        android.util.Log.i("DemoTicket2", "SAVED_ITEM=${saved.item.id} AT=$at")

        // 等待系统闹钟投递：轮询通知管理器，出现我们频道的高优先级通知即视为到点
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 90_000
        var fired = false
        while (System.currentTimeMillis() < deadline) {
            val active = nm.activeNotifications
            if (active.any { it.id == AlarmReceiver.notificationIdFor(saved.item.id) }) {
                fired = true; break
            }
            Thread.sleep(1000)
        }
        android.util.Log.i("DemoTicket2", "ALARM_NOTIFICATION_FIRED=$fired")
        assertTrue("系统闹钟应真实投递并出现通知", fired)

        runBlocking { app.useCases.deleteItem(saved.item.id) }
    }
}
