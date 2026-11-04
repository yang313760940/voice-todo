package com.voicetodo.alarm

import android.content.Intent

internal data class ReminderToken(
    val itemId: String,
    val revision: Long,
    val atMillis: Long,
    val reminderId: String = itemId,
) {
    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
        putExtra(SystemReminderScheduler.EXTRA_REVISION, revision)
        putExtra(SystemReminderScheduler.EXTRA_AT_MILLIS, atMillis)
        putExtra(SystemReminderScheduler.EXTRA_REMINDER_ID, reminderId)
    }

    companion object {
        fun from(intent: Intent?): ReminderToken? {
            val id = intent?.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: return null
            if (!intent.hasExtra(SystemReminderScheduler.EXTRA_REVISION) ||
                !intent.hasExtra(SystemReminderScheduler.EXTRA_AT_MILLIS)) return null
            return ReminderToken(id,
                intent.getLongExtra(SystemReminderScheduler.EXTRA_REVISION, Long.MIN_VALUE),
                intent.getLongExtra(SystemReminderScheduler.EXTRA_AT_MILLIS, Long.MIN_VALUE),
                intent.getStringExtra(SystemReminderScheduler.EXTRA_REMINDER_ID) ?: id)
        }
    }
}
