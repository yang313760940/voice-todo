package com.voicetodo.alarm

import android.content.Context
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** 设备加密区中的最小提醒镜像；刻意不包含事项正文、朗读设置或 AI 配置。 */
internal data class DeviceProtectedReminder(
    val itemId: String,
    val reminderId: String,
    val atMillis: Long,
    val revision: Long,
    val deliveredAtMillis: Long? = null,
) {
    val key: String get() = key(itemId, reminderId)
    val token: ReminderToken get() = ReminderToken(itemId, revision, atMillis, reminderId)

    companion object {
        fun key(itemId: String, reminderId: String) = "$itemId\u0000$reminderId"
    }
}

internal class DeviceProtectedReminderStore(context: Context) {
    private val preferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    fun records(): List<DeviceProtectedReminder> = synchronized(lock) { readLocked() }

    fun pending(now: Instant): List<DeviceProtectedReminder> = records().filter {
        it.deliveredAtMillis == null && it.atMillis > now.toEpochMilli()
    }

    fun delivered(): List<DeviceProtectedReminder> = records().filter { it.deliveredAtMillis != null }

    fun upsert(token: ReminderToken): Boolean = synchronized(lock) {
        val records = readLocked().associateByTo(linkedMapOf(), DeviceProtectedReminder::key)
        val key = DeviceProtectedReminder.key(token.itemId, token.reminderId)
        val current = records[key]
        records[key] = DeviceProtectedReminder(
            itemId = token.itemId,
            reminderId = token.reminderId,
            atMillis = token.atMillis,
            revision = token.revision,
            deliveredAtMillis = current?.deliveredAtMillis?.takeIf {
                current.atMillis == token.atMillis && current.revision == token.revision
            },
        )
        writeLocked(records.values)
    }

    fun removeItem(itemId: String): Boolean = synchronized(lock) {
        writeLocked(readLocked().filterNot { it.itemId == itemId })
    }

    fun removeReminder(itemId: String, reminderId: String): Boolean = synchronized(lock) {
        writeLocked(readLocked().filterNot { it.itemId == itemId && it.reminderId == reminderId })
    }

    fun retainValid(validKeys: Set<String>): Boolean = synchronized(lock) {
        writeLocked(readLocked().filter { it.key in validKeys })
    }

    fun deliverIfCurrent(
        token: ReminderToken,
        deliveredAt: Instant,
        publish: () -> Boolean,
    ): Boolean = synchronized(lock) {
        val records = readLocked().associateByTo(linkedMapOf(), DeviceProtectedReminder::key)
        val key = DeviceProtectedReminder.key(token.itemId, token.reminderId)
        val current = records[key] ?: return@synchronized false
        if (current.revision != token.revision || current.atMillis != token.atMillis ||
            current.deliveredAtMillis != null) return@synchronized false
        if (!publish()) return@synchronized false
        records[key] = current.copy(deliveredAtMillis = deliveredAt.toEpochMilli())
        writeLocked(records.values)
    }

    private fun readLocked(): List<DeviceProtectedReminder> {
        val array = JSONArray(preferences.getString(KEY_RECORDS, "[]"))
        return (0 until array.length()).map { index ->
            val record = array.getJSONObject(index)
            DeviceProtectedReminder(
                itemId = record.getString("itemId"),
                reminderId = record.getString("reminderId"),
                atMillis = record.getLong("atMillis"),
                revision = record.getLong("revision"),
                deliveredAtMillis = record.optLong("deliveredAtMillis").takeIf {
                    record.has("deliveredAtMillis")
                },
            )
        }.sortedWith(compareBy<DeviceProtectedReminder> { it.itemId }.thenBy { it.reminderId })
    }

    private fun writeLocked(records: Collection<DeviceProtectedReminder>): Boolean {
        val array = JSONArray()
        records.sortedWith(compareBy<DeviceProtectedReminder> { it.itemId }.thenBy { it.reminderId })
            .forEach { reminder ->
                array.put(JSONObject().apply {
                    put("itemId", reminder.itemId)
                    put("reminderId", reminder.reminderId)
                    put("atMillis", reminder.atMillis)
                    put("revision", reminder.revision)
                    reminder.deliveredAtMillis?.let { put("deliveredAtMillis", it) }
                })
            }
        return preferences.edit().putString(KEY_RECORDS, array.toString()).commit()
    }

    companion object {
        const val PREFERENCES = "direct_boot_reminders"
        const val KEY_RECORDS = "records"
    }
}
