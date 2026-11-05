package com.voicetodo.alarm

import android.app.NotificationManager
import android.content.Context
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.TodoUseCases
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 在设备加密区的最小镜像与解锁后的主数据之间执行单向恢复和最终对账。 */
internal class DirectBootReminderCoordinator(
    private val context: Context,
    private val store: DeviceProtectedReminderStore,
    private val scheduler: SystemReminderScheduler,
    private val useCases: TodoUseCases,
    private val recovery: ReminderRecoveryCoordinator,
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Mutex()

    fun restoreBeforeUnlock() {
        store.pending(now()).forEach(scheduler::restoreBeforeUnlock)
    }

    suspend fun reconcileAfterUnlock() = lock.withLock {
        val notifications = context.getSystemService(NotificationManager::class.java)
        store.delivered().forEach { reminder ->
            val imported = useCases.deliverReminder(
                reminder.itemId,
                reminder.reminderId,
                reminder.revision,
                reminder.atMillis,
            ) { item ->
                runCatching {
                    notifications.notify(
                        AlarmReceiver.notificationIdFor(item.id, reminder.reminderId),
                        AlarmNotification.build(
                            context,
                            item.id,
                            item.title,
                            reminder.reminderId,
                            reminder.revision,
                            silent = true,
                            allowFullScreen = false,
                        ),
                    )
                    true
                }.getOrDefault(false)
            }
            if (!imported) {
                notifications.cancel(AlarmReceiver.notificationIdFor(reminder.itemId, reminder.reminderId))
            } else {
                scheduler.cancelReminder(reminder.itemId, reminder.reminderId)
            }
        }

        recovery.reconcile()
        store.retainValid(validReminderKeys())
    }

    private suspend fun validReminderKeys(): Set<String> {
        val current = now()
        return buildSet {
            useCases.listItems().filter { it.completedAt == null }.forEach { item ->
                if (item.reminders.isEmpty()) {
                    val at = item.remindAt
                    if (at != null && at.isAfter(current) && !useCases.wasDelivered(item)) {
                        add(DeviceProtectedReminder.key(item.id, item.id))
                    }
                    return@forEach
                }
                item.reminders.filter {
                    it.at.isAfter(current) && it.deliveredAt == null &&
                        it.outcome != ReminderOutcome.NotRequested
                }.forEach {
                    add(DeviceProtectedReminder.key(item.id, it.id))
                }
            }
        }
    }

    companion object {
        fun restoreBeforeUnlock(
            store: DeviceProtectedReminderStore,
            scheduler: SystemReminderScheduler,
            now: Instant = Instant.now(),
        ) {
            store.pending(now).forEach(scheduler::restoreBeforeUnlock)
        }
    }
}
