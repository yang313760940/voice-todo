package com.voicetodo.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.voicetodo.VoiceTodoApp

/**
 * 开机/应用升级/精确闹钟权限恢复后重建未来提醒。
 * 不使用 directBootAware：解锁后投递即可（首版切片；Direct Boot 见规格后续要求）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                val result = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        (context.applicationContext as VoiceTodoApp).useCases.reconcileReminders()
                    } catch (e: Exception) {
                        android.util.Log.e("BootReceiver", "Migration/reconcile failed", e)
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }
}
