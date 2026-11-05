package com.voicetodo.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.voicetodo.VoiceTodoApp

/**
 * 首次解锁前仅从设备加密区恢复最小提醒；解锁后再读取主数据并完整对账。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                val result = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val app = context.applicationContext as VoiceTodoApp
                        val unlocked = context.getSystemService(android.os.UserManager::class.java).isUserUnlocked
                        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED || !unlocked) {
                            app.restoreBeforeUnlock()
                        } else {
                            app.reconcileAfterUnlock()
                        }
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
