package com.voicetodo.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟到点接收器：由系统 AlarmManager 投递（进程退出/锁屏亦然）。
 * 职责：将用户设定的精确闹钟交给前台服务，由服务发布全屏通知并响铃。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SystemReminderScheduler.ACTION_FIRE) return
        val itemId = intent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: return
        val title = intent.getStringExtra(SystemReminderScheduler.EXTRA_TITLE) ?: "提醒"

        // 用户设定的精确闹钟属于后台启动 FGS 的例外，声音不依赖 Activity 能否展示。
        val service = Intent(context, RingingService::class.java).apply {
            putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
            putExtra(SystemReminderScheduler.EXTRA_TITLE, title)
        }
        try {
            context.startForegroundService(service)
        } catch (e: Exception) {
            // 后台启动 FGS 受限：记录而非静默吞掉，便于诊断。
            android.util.Log.w(TAG, "startForegroundService 失败（后台受限/无权限）", e)
            showFallbackNotification(context, itemId, title)
        }
    }

    private fun showFallbackNotification(context: Context, itemId: String, title: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm.notify(notificationIdFor(itemId), AlarmNotification.build(context, itemId, title))
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "提醒通知不可用", e)
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        fun notificationIdFor(itemId: String): Int =
            SystemReminderScheduler.requestCodeFor(itemId) and 0x0FFFFFFF
    }
}
