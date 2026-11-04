package com.voicetodo.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 处理“关闭本次提醒”动作：停止响铃服务并清除通知；不完成事项。 */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val itemId = intent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: return

        context.stopService(Intent(context, RingingService::class.java))
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(AlarmReceiver.notificationIdFor(itemId))
    }

    companion object {
        const val ACTION_STOP = "com.voicetodo.action.ALARM_STOP"
    }
}
