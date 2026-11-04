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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** adb 验收驱动：直接调用真实用例，不启用 instrumentation，也不进入发布包。 */
class AlarmTestDriverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VoiceTodoApp
        if (intent.action == "com.voicetodo.debug.EXIT") {
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
        try {
        when (intent.action) {
            "com.voicetodo.debug.SCHEDULE" -> {
                val at = LocalDateTime.now().plusSeconds(45).withNano(0)
                val atInstant = at.atZone(ZoneId.systemDefault()).toInstant()
                val saved = app.useCases.confirmDraft("锁屏响铃验收", atInstant)
                pending.resultCode = if (saved.outcome == com.voicetodo.domain.ReminderOutcome.Scheduled) 0 else 1
                pending.resultData = "scheduledItem=${saved.item.id} fireAt=${atInstant.toEpochMilli()}"
            }
            "com.voicetodo.debug.CLEANUP" -> {
                val id = intent.getStringExtra("itemId") ?: return@launch
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                if (nm.activeNotifications.any { it.id == AlarmReceiver.notificationIdFor(id) }) {
                    AlarmActionReceiver().onReceive(context, Intent().apply {
                        action = AlarmActionReceiver.ACTION_STOP
                        putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, id)
                    })
                }
                app.useCases.deleteItem(id)
            }
        }
        } catch (e: Exception) {
            pending.resultCode = 1
            pending.resultData = e.message
            android.util.Log.e("AlarmTestDriver", "Driver failed", e)
        } finally { pending.finish() }
        }
    }
}
