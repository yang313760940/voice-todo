package com.voicetodo.debug

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.AlarmReceiver
import com.voicetodo.alarm.SystemReminderScheduler
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 仅调试包使用的 adb 恢复验收驱动，不进入发布包。 */
class ReminderRecoveryTestDriverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as VoiceTodoApp
                when (intent.action) {
                    ACTION_SCHEDULE -> {
                        val at = Instant.now().plusSeconds(30 * 60)
                        val result = app.useCases.confirmDraft("恢复验收", at)
                        pendingResult.resultData = "itemId=${result.item.id} fireAt=${at.toEpochMilli()}"
                    }
                    ACTION_DROP_SYSTEM -> {
                        val itemId = requireNotNull(intent.getStringExtra(EXTRA_ITEM_ID))
                        app.scheduler.cancel(itemId)
                        pendingResult.resultData = "dropped=$itemId"
                    }
                    ACTION_RECONCILE -> {
                        app.reminderRecovery.reconcile()
                        pendingResult.resultData = "reconciled=true"
                    }
                    ACTION_STATUS -> {
                        val itemId = requireNotNull(intent.getStringExtra(EXTRA_ITEM_ID))
                        pendingResult.resultData = "pending=${pendingIntent(context, itemId) != null}"
                    }
                    ACTION_CLEANUP -> {
                        val itemId = requireNotNull(intent.getStringExtra(EXTRA_ITEM_ID))
                        app.useCases.deleteItem(itemId)
                        pendingResult.resultData = "deleted=$itemId"
                    }
                }
                pendingResult.resultCode = 0
            } catch (error: Exception) {
                pendingResult.resultCode = 1
                pendingResult.resultData = error.message
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun pendingIntent(context: Context, itemId: String): PendingIntent? {
        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = SystemReminderScheduler.ACTION_FIRE
            data = Uri.Builder().scheme("voicetodo").authority("alarm").appendPath(itemId).build()
        }
        return PendingIntent.getBroadcast(
            context,
            SystemReminderScheduler.requestCodeFor(itemId),
            alarmIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_SCHEDULE = "com.voicetodo.debug.RECOVERY_SCHEDULE"
        const val ACTION_DROP_SYSTEM = "com.voicetodo.debug.RECOVERY_DROP_SYSTEM"
        const val ACTION_RECONCILE = "com.voicetodo.debug.RECOVERY_RECONCILE"
        const val ACTION_STATUS = "com.voicetodo.debug.RECOVERY_STATUS"
        const val ACTION_CLEANUP = "com.voicetodo.debug.RECOVERY_CLEANUP"
        const val EXTRA_ITEM_ID = "itemId"
    }
}
