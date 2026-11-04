package com.voicetodo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.AlarmActionReceiver
import com.voicetodo.alarm.AlarmReceiver
import com.voicetodo.alarm.SystemReminderScheduler
import java.time.LocalDateTime
import java.time.ZoneId

/** adb 验收驱动：直接调用真实用例，不启用 instrumentation，也不进入发布包。 */
class AlarmTestDriverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VoiceTodoApp
        when (intent.action) {
            "com.voicetodo.debug.SCHEDULE" -> {
                val at = LocalDateTime.now().plusSeconds(45).withNano(0)
                val saved = app.useCases.confirmDraft("锁屏响铃验收", at)
                resultCode = if (saved.scheduled) 0 else 1
                resultData = "scheduledItem=${saved.item.id} fireAt=${at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}"
            }
            "com.voicetodo.debug.CLEANUP" -> {
                val id = intent.getStringExtra("itemId") ?: return
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                if (nm.activeNotifications.any { it.id == AlarmReceiver.notificationIdFor(id) }) {
                    AlarmActionReceiver().onReceive(context, Intent().apply {
                        action = AlarmActionReceiver.ACTION_STOP
                        putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, id)
                    })
                }
                app.useCases.deleteItem(id)
            }
            "com.voicetodo.debug.EXIT" -> {
                // 让广播完成后终止自身；系统包 stopped 标志和已注册闹钟均不受影响。
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
            }
        }
    }
}
