package com.voicetodo.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.voicetodo.MainActivity
import com.voicetodo.R
import com.voicetodo.VoiceTodoApp

/**
 * 闹钟到点接收器：由系统 AlarmManager 投递（进程退出/锁屏亦然）。
 * 职责：发通知（高优先级）→ 拉起全屏提醒界面 → 启动持续响铃前台服务。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SystemReminderScheduler.ACTION_FIRE) return
        val itemId = intent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: return
        val title = intent.getStringExtra(SystemReminderScheduler.EXTRA_TITLE) ?: "提醒"

        showNotification(context, itemId, title)

        // 全屏提醒界面（有权限时）
        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
            putExtra(SystemReminderScheduler.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(ringIntent)
        } catch (e: Exception) {
            // 后台启动受限时仍有通知与响铃路径
        }

        // 持续响铃前台服务（默认 60 秒后停止发声，保留通知）
        val service = Intent(context, RingingService::class.java).apply {
            putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
            putExtra(SystemReminderScheduler.EXTRA_TITLE, title)
        }
        try {
            context.startForegroundService(service)
        } catch (e: Exception) {
            // 无 FGS 权限时保留通知
        }
    }

    private fun showNotification(context: Context, itemId: String, title: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // 统一用 requestCodeFor(itemId) 派生，配合不同的 data URI 区分 open/stop，
        // 避免与闹钟本身或其它事项的 PendingIntent 碰撞（过去用 hashCode()/hashCode()+7 会撞）。
        val base = SystemReminderScheduler.requestCodeFor(itemId)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.parse("voicetodo://open/$itemId")
        }
        val openPi = PendingIntent.getActivity(
            context, base + OPEN_REQUEST_OFFSET, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_STOP
            data = android.net.Uri.parse("voicetodo://stop/$itemId")
            putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
        }
        val stopPi = PendingIntent.getBroadcast(
            context, base + STOP_REQUEST_OFFSET, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, VoiceTodoApp.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("语音提醒")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, "关闭提醒", stopPi)
            .setContentIntent(openPi)
            .build()
        try {
            nm.notify(notificationIdFor(itemId), notif)
        } catch (e: SecurityException) {
            // 未授予 POST_NOTIFICATIONS：通知不可见，但服务与界面路径仍尝试
        }
    }

    companion object {
        // 与闹钟 showPi（base+0x200000）区分开，open/stop 各用独立偏移。
        private const val OPEN_REQUEST_OFFSET = 0x100000
        private const val STOP_REQUEST_OFFSET = 0x080000

        fun notificationIdFor(itemId: String): Int =
            SystemReminderScheduler.requestCodeFor(itemId) and 0x0FFFFFFF
    }
}
