package com.voicetodo.alarm

import android.content.Context
import com.voicetodo.domain.TodoUseCases
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 串行执行恢复对账，并持久化上次成功对账的时区。 */
class ReminderRecoveryCoordinator(
    context: Context,
    private val useCases: TodoUseCases,
    private val currentZone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Mutex()

    suspend fun reconcile() = lock.withLock {
        val zone = currentZone()
        val previous = preferences.getString(KEY_LAST_ZONE, null)?.let(ZoneId::of)
        useCases.reconcileReminders(previousZone = previous, currentZone = zone)
        check(preferences.edit().putString(KEY_LAST_ZONE, zone.id).commit()) {
            "无法保存提醒恢复时区"
        }
    }

    companion object {
        const val PREFERENCES = "reminder_recovery"
        const val KEY_LAST_ZONE = "last_zone"
    }
}
