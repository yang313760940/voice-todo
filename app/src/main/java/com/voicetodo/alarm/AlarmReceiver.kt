package com.voicetodo.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voicetodo.VoiceTodoApp
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.isCurrentReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 闹钟到点接收器：由系统 AlarmManager 投递（进程退出/锁屏亦然）。
 * 职责：将用户设定的精确闹钟交给前台服务，由服务发布全屏通知并响铃。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SystemReminderScheduler.ACTION_FIRE) return
        if (ReminderToken.from(intent) == null) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deliver(context, intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "读取当前事项失败，拒绝投递", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Same delivery entry used by the asynchronous receiver and host-side Android tests. */
    suspend fun deliver(context: Context, intent: Intent) {
        if (intent.action != SystemReminderScheduler.ACTION_FIRE) return
        val token = ReminderToken.from(intent) ?: return
        val itemId = token.itemId
        val repo = (context.applicationContext as VoiceTodoApp).repository
        val item = repo.findById(itemId)
        if (item == null || !item.isCurrentReminder(itemId, token.revision, token.atMillis) ||
            repo.deliveredAt(itemId, token.revision) != null) {
            android.util.Log.w(
                TAG,
                "忽略过期或已投递提醒 item=$itemId revision=${token.revision}",
            )
            return
        }

        // 用户设定的精确闹钟属于后台启动 FGS 的例外，声音不依赖 Activity 能否展示。
        val service = token.putInto(Intent(context, RingingService::class.java))
        try {
            context.startForegroundService(service)
        } catch (e: Exception) {
            // 后台启动 FGS 受限：记录而非静默吞掉，便于诊断。
            android.util.Log.w(TAG, "startForegroundService 失败（后台受限/无权限）", e)
            repo.deliverReminder(itemId, token.revision, token.atMillis, Instant::now) {
                showFallbackNotification(context, it)
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        fun notificationIdFor(itemId: String): Int =
            SystemReminderScheduler.requestCodeFor(itemId) and 0x0FFFFFFF

        internal fun showFallbackNotification(context: Context, item: TodoItem): Boolean = try {
            context.getSystemService(NotificationManager::class.java).notify(
        notificationIdFor(item.id), AlarmNotification.build(context, item.id, item.title))
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "提醒通知不可用", e)
            false
        }
    }
}
