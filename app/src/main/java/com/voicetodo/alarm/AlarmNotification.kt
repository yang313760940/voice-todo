package com.voicetodo.alarm

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.voicetodo.VoiceTodoApp

/** 前台服务和服务启动失败时使用同一份可关闭的闹钟通知。 */
internal object AlarmNotification {
    fun build(context: Context, itemId: String, title: String): Notification {
        val base = SystemReminderScheduler.requestCodeFor(itemId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            // data 参与 PendingIntent 身份比较；extras 不参与，不能只依赖 hashCode。
            data = Uri.Builder().scheme("voicetodo").authority("ring").appendPath(itemId).build()
            putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
            putExtra(SystemReminderScheduler.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val ringPi = PendingIntent.getActivity(context, base + 0x040000, ringIntent, flags)
        val stopIntent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_STOP
            data = Uri.Builder().scheme("voicetodo").authority("stop").appendPath(itemId).build()
            putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
        }
        val stopPi = PendingIntent.getBroadcast(context, base + 0x080000, stopIntent, flags)
        return NotificationCompat.Builder(context, VoiceTodoApp.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("语音提醒")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(ringPi)
            .setFullScreenIntent(ringPi, true)
            .addAction(0, "关闭提醒", stopPi)
            .build()
    }
}
