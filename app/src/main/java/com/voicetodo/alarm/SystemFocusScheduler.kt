package com.voicetodo.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.voicetodo.VoiceTodoApp
import com.voicetodo.domain.FocusScheduler
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SystemFocusScheduler(private val context: Context) : FocusScheduler {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(sessionId: String, at: Instant): Boolean = try {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(at.toEpochMilli(), showPendingIntent()),
            firePendingIntent(sessionId, at),
        )
        true
    } catch (_: SecurityException) {
        false
    }

    override fun cancel(sessionId: String) {
        val pendingIntent = firePendingIntent(sessionId, Instant.EPOCH)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun showPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_SHOW,
        Intent(context, com.voicetodo.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun firePendingIntent(sessionId: String, at: Instant): PendingIntent {
        val intent = Intent(context, FocusAlarmReceiver::class.java).apply {
            action = ACTION_FOCUS_END
            data = Uri.parse("voicetodo://focus-end/$sessionId")
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_AT_MILLIS, at.toEpochMilli())
        }
        return PendingIntent.getBroadcast(
            context, requestCodeFor(sessionId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FOCUS_END = "com.voicetodo.action.FOCUS_END"
        const val EXTRA_SESSION_ID = "extra_focus_session_id"
        const val EXTRA_AT_MILLIS = "extra_focus_at_millis"
        private const val REQUEST_SHOW = 0x300000

        fun requestCodeFor(sessionId: String): Int =
            (sessionId.hashCode() and 0x7FFFFFF) and 0x1FFFFF
    }
}

class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SystemFocusScheduler.ACTION_FOCUS_END) return
        val sessionId = intent.getStringExtra(SystemFocusScheduler.EXTRA_SESSION_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as VoiceTodoApp
                app.focusUseCases.reconcileEnd()
                val notification = NotificationCompat.Builder(context, VoiceTodoApp.CHANNEL_REMINDER)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("番茄结束")
                    .setContentText("阶段已结束，请打开应用确认；不会自动开始下一阶段。")
                    .setAutoCancel(true)
                    .build()
                context.getSystemService(NotificationManager::class.java)
                    .notify(SystemFocusScheduler.requestCodeFor(sessionId), notification)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
