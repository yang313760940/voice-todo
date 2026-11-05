package com.voicetodo.data

import com.voicetodo.domain.BACKUP_FORMAT
import com.voicetodo.domain.BACKUP_FORMAT_VERSION
import com.voicetodo.domain.BackupCodec
import com.voicetodo.domain.BackupData
import com.voicetodo.domain.BackupDocument
import com.voicetodo.domain.BackupRejectedException
import com.voicetodo.domain.FocusPhase
import com.voicetodo.domain.FocusSession
import com.voicetodo.domain.FocusSessionState
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoList
import com.voicetodo.domain.TodoReminder
import com.voicetodo.settings.AppPreferences
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1 备份 JSON。解码是严格校验入口：字段缺失、枚举未知、id 重复、清单/重复安排引用断裂
 * 都会以 [BackupRejectedException] 拒绝，调用方不会看到部分解析结果。
 */
object JsonBackupCodec : BackupCodec {
    private const val MAX_TEXT_LENGTH = 32 * 1024 * 1024

    override fun encode(data: BackupData, exportedAt: Instant, appVersion: String): String =
        JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("formatVersion", BACKUP_FORMAT_VERSION)
            put("exportedAt", exportedAt.toString())
            put("appVersion", appVersion)
            put("data", JSONObject().apply {
                put("preferences", JSONObject().apply {
                    put("theme", data.preferences.theme.name)
                    put("fontSize", data.preferences.fontSize.name)
                })
                put("focusSettings", JSONObject().apply {
                    put("focusMinutes", data.focusSettings.focusMinutes)
                    put("shortBreakMinutes", data.focusSettings.shortBreakMinutes)
                    put("longBreakMinutes", data.focusSettings.longBreakMinutes)
                    put("longBreakEvery", data.focusSettings.longBreakEvery)
                })
                put("lists", JSONArray().apply {
                    data.lists.forEach { list ->
                        put(JSONObject().apply {
                            put("id", list.id)
                            put("name", list.name)
                            put("createdAt", list.createdAt.toString())
                        })
                    }
                })
                put("items", JSONArray().apply {
                    data.items.forEach { put(it.toJson()) }
                })
                put("focusSessions", JSONArray().apply {
                    data.focusSessions.forEach { put(it.toJson()) }
                })
            })
        }.toString(2)

    override fun decode(text: String): BackupDocument {
        if (text.length > MAX_TEXT_LENGTH) reject("备份文件过大")
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            reject("备份文件不是有效的 JSON")
        }
        if (root.optionalString("format") != BACKUP_FORMAT) {
            reject("不是 VoiceTodo 备份文件")
        }
        val version = root.requiredInt("formatVersion", "备份文件")
        if (version <= 0) reject("备份格式版本无效")
        if (version > BACKUP_FORMAT_VERSION) {
            reject("备份格式版本 $version 高于当前支持的 $BACKUP_FORMAT_VERSION，请先升级应用")
        }
        val exportedAt = parseInstant(
            root.optionalString("exportedAt") ?: reject("备份文件缺少导出时间"),
            "导出时间",
        )
        val appVersion = root.optionalString("appVersion").orEmpty()
        val dataJson = root.optJSONObject("data") ?: reject("备份文件缺少数据内容")
        val data = decodeData(dataJson)
        return BackupDocument(version, exportedAt, appVersion, data)
    }

    private fun decodeData(dataJson: JSONObject): BackupData {
        val preferencesJson = dataJson.optJSONObject("preferences")
            ?: reject("备份文件缺少偏好设置")
        val preferences = AppPreferences(
            theme = enumRequired<ThemePreference>(
                preferencesJson.optionalString("theme"),
                "偏好设置的主题",
            ),
            fontSize = enumRequired<FontSizePreference>(
                preferencesJson.optionalString("fontSize"),
                "偏好设置的字体大小",
            ),
        )

        val focusJson = dataJson.optJSONObject("focusSettings")
            ?: reject("备份文件缺少番茄设置")
        val focusSettings = FocusSettings(
            focusMinutes = focusJson.requiredPositiveInt("focusMinutes", "番茄设置"),
            shortBreakMinutes = focusJson.requiredPositiveInt("shortBreakMinutes", "番茄设置"),
            longBreakMinutes = focusJson.requiredPositiveInt("longBreakMinutes", "番茄设置"),
            longBreakEvery = focusJson.requiredPositiveInt("longBreakEvery", "番茄设置"),
        )

        val lists = decodeLists(dataJson.optJSONArray("lists") ?: JSONArray())
        val listIds = lists.map { it.id }.toSet()
        if (listIds.size != lists.size) reject("备份中的清单 id 重复")

        val items = decodeItems(
            dataJson.optJSONArray("items") ?: reject("备份文件缺少事项数据"),
            listIds,
        )
        val focusSessions = decodeFocusSessions(dataJson.optJSONArray("focusSessions") ?: JSONArray())

        return BackupData(
            items = items,
            lists = lists,
            focusSessions = focusSessions,
            focusSettings = focusSettings,
            preferences = preferences,
        )
    }

    private fun decodeLists(array: JSONArray): List<TodoList> = buildList {
        for (index in 0 until array.length()) {
            val label = "第 ${index + 1} 个清单"
            val json = array.optJSONObject(index) ?: reject("$label 格式无效")
            add(
                TodoList(
                    id = json.requiredString("id", label),
                    name = json.requiredString("name", label).trim().also {
                        if (it.isEmpty()) reject("$label 的名称不能为空")
                    },
                    createdAt = parseInstant(
                        json.optionalString("createdAt") ?: reject("$label 缺少创建时间"),
                        "$label 的创建时间",
                    ),
                ),
            )
        }
    }

    private fun decodeItems(array: JSONArray, listIds: Set<String>): List<TodoItem> {
        val items = buildList {
            for (index in 0 until array.length()) {
                val label = "第 ${index + 1} 个事项"
                val json = array.optJSONObject(index) ?: reject("$label 格式无效")
                add(decodeItem(json, label, listIds))
            }
        }
        if (items.map { it.id }.toSet().size != items.size) reject("备份中的事项 id 重复")

        val byId = items.associateBy { it.id }
        for (item in items) {
            if (item.seriesId != null) {
                if (item.occurrenceDate == null) reject("重复实例「${item.title}」缺少发生日期")
                if (item.recurrence != null) reject("重复实例「${item.title}」不能同时保存重复规则")
                val series = byId[item.seriesId]
                    ?: reject("重复实例「${item.title}」引用的重复安排不存在")
                if (series.recurrence == null) {
                    reject("重复实例「${item.title}」引用的安排缺少重复规则")
                }
            }
        }
        return items
    }

    private fun decodeItem(json: JSONObject, label: String, listIds: Set<String>): TodoItem {
        val id = json.requiredString("id", label)
        val title = json.optionalString("title")?.trim().orEmpty()
        if (title.isEmpty()) reject("$label 的标题不能为空")
        val listId = json.optionalString("listId")
        if (listId != null && listId !in listIds) reject("$label 引用的清单不存在")
        val dueAt = optionalInstant(json, "dueAt", label)
        val recurrenceText = json.optionalString("recurrenceRule")
        val recurrence = recurrenceText?.let {
            try {
                RecurrenceCodec.decode(it)!!.also { rule -> rule.validate() }
            } catch (e: Exception) {
                reject("$label 的重复规则无效")
            }
        }
        val reminders = decodeReminders(json.optJSONArray("reminders"), label, dueAt)
        val revision = json.optionalLong("reminderRevision") ?: 0L
        if (revision < 0) reject("$label 的提醒修订号无效")

        return TodoItem(
            id = id,
            title = title,
            remindAt = optionalInstant(json, "remindAt", label),
            plannedDate = optionalDate(json, "plannedDate", label),
            dueAt = dueAt,
            completedAt = optionalInstant(json, "completedAt", label),
            createdAt = parseInstant(
                json.optionalString("createdAt") ?: reject("$label 缺少创建时间"),
                "$label 的创建时间",
            ),
            reminderRevision = revision,
            reminders = reminders,
            quadrant = enumRequired<Quadrant>(
                json.optionalString("quadrant") ?: Quadrant.NotImportantNotUrgent.name,
                "$label 的象限",
            ),
            suggestedQuadrant = json.optionalString("suggestedQuadrant")?.let {
                enumRequired<Quadrant>(it, "$label 的建议象限")
            },
            listId = listId,
            seriesId = json.optionalString("seriesId"),
            occurrenceDate = optionalDate(json, "occurrenceDate", label),
            recurrence = recurrence,
            persistentRing = json.optionalBoolean("persistentRing") ?: false,
            readAloud = json.optionalBoolean("readAloud") ?: false,
            readAloudWhenLocked = json.optionalBoolean("readAloudWhenLocked") ?: false,
            dismissedAt = optionalInstant(json, "dismissedAt", label),
        )
    }

    private fun decodeReminders(
        array: JSONArray?,
        itemLabel: String,
        dueAt: Instant?,
    ): List<TodoReminder> {
        if (array == null) return emptyList()
        val reminders = buildList {
            for (index in 0 until array.length()) {
                val label = "$itemLabel 的第 ${index + 1} 个提醒"
                val json = array.optJSONObject(index) ?: reject("$label 格式无效")
                val beforeDue = json.optionalString("beforeDue")?.let {
                    try {
                        Duration.parse(it)
                    } catch (e: Exception) {
                        reject("$label 的提前时长无效")
                    }.also { duration ->
                        if (duration.isNegative || duration.isZero) reject("$label 的提前时长必须大于零")
                    }
                }
                if (beforeDue != null && dueAt == null) reject("$label 是相对提醒，但事项缺少截止时间")
                val revision = json.optionalLong("revision") ?: 0L
                if (revision < 0) reject("$label 的修订号无效")
                add(
                    TodoReminder(
                        id = json.requiredString("id", label),
                        at = parseInstant(
                            json.optionalString("at") ?: reject("$label 缺少提醒时间"),
                            "$label 的提醒时间",
                        ),
                        beforeDue = beforeDue,
                        revision = revision,
                        outcome = enumRequired<ReminderOutcome>(
                            json.optionalString("outcome") ?: ReminderOutcome.NotReady.name,
                            "$label 的状态",
                        ),
                        deliveredAt = optionalInstant(json, "deliveredAt", label),
                    ),
                )
            }
        }
        if (reminders.map { it.id }.toSet().size != reminders.size) {
            reject("$itemLabel 的提醒 id 重复")
        }
        return reminders
    }

    private fun decodeFocusSessions(array: JSONArray): List<FocusSession> {
        val sessions = buildList {
            for (index in 0 until array.length()) {
                val label = "第 ${index + 1} 条专注记录"
                val json = array.optJSONObject(index) ?: reject("$label 格式无效")
                val duration = json.requiredLong("durationSeconds", label)
                if (duration <= 0) reject("$label 的时长无效")
                val accumulated = json.optionalLong("accumulatedFocusSeconds") ?: 0L
                if (accumulated < 0) reject("$label 的累计时长无效")
                val completed = json.optionalInt("completedPomodoros") ?: 0
                if (completed < 0) reject("$label 的完成番茄数无效")
                add(
                    FocusSession(
                        id = json.requiredString("id", label),
                        phase = enumRequired(json.optionalString("phase"), "$label 的阶段"),
                        startedAt = parseInstant(
                            json.optionalString("startedAt") ?: reject("$label 缺少开始时间"),
                            "$label 的开始时间",
                        ),
                        endsAt = parseInstant(
                            json.optionalString("endsAt") ?: reject("$label 缺少结束时间"),
                            "$label 的结束时间",
                        ),
                        lastResumedAt = parseInstant(
                            json.optionalString("lastResumedAt") ?: reject("$label 缺少恢复时间"),
                            "$label 的恢复时间",
                        ),
                        durationSeconds = duration,
                        pausedAt = optionalInstant(json, "pausedAt", label),
                        accumulatedFocusSeconds = accumulated,
                        completedPomodoros = completed,
                        itemId = json.optionalString("itemId"),
                        state = enumRequired(json.optionalString("state"), "$label 的状态"),
                        endedAt = optionalInstant(json, "endedAt", label),
                    ),
                )
            }
        }
        if (sessions.map { it.id }.toSet().size != sessions.size) reject("备份中的专注记录 id 重复")
        return sessions
    }

    private fun TodoItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        putNullable("remindAt", remindAt?.toString())
        putNullable("plannedDate", plannedDate?.toString())
        putNullable("dueAt", dueAt?.toString())
        putNullable("completedAt", completedAt?.toString())
        put("createdAt", createdAt.toString())
        put("reminderRevision", reminderRevision)
        put("reminders", JSONArray().apply {
            reminders.forEach { reminder ->
                put(JSONObject().apply {
                    put("id", reminder.id)
                    put("at", reminder.at.toString())
                    putNullable("beforeDue", reminder.beforeDue?.toString())
                    put("revision", reminder.revision)
                    put("outcome", reminder.outcome.name)
                    putNullable("deliveredAt", reminder.deliveredAt?.toString())
                })
            }
        })
        put("quadrant", quadrant.name)
        putNullable("suggestedQuadrant", suggestedQuadrant?.name)
        putNullable("listId", listId)
        putNullable("recurrenceRule", recurrence?.let { RecurrenceCodec.encode(it) })
        putNullable("seriesId", seriesId)
        putNullable("occurrenceDate", occurrenceDate?.toString())
        put("persistentRing", persistentRing)
        put("readAloud", readAloud)
        put("readAloudWhenLocked", readAloudWhenLocked)
        putNullable("dismissedAt", dismissedAt?.toString())
    }

    private fun FocusSession.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("phase", phase.name)
        put("startedAt", startedAt.toString())
        put("endsAt", endsAt.toString())
        put("lastResumedAt", lastResumedAt.toString())
        put("durationSeconds", durationSeconds)
        putNullable("pausedAt", pausedAt?.toString())
        put("accumulatedFocusSeconds", accumulatedFocusSeconds)
        put("completedPomodoros", completedPomodoros)
        putNullable("itemId", itemId)
        put("state", state.name)
        putNullable("endedAt", endedAt?.toString())
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun optionalInstant(json: JSONObject, key: String, label: String): Instant? =
        json.optionalString(key)?.let { parseInstant(it, "$label 的 $key") }

    private fun optionalDate(json: JSONObject, key: String, label: String): LocalDate? =
        json.optionalString(key)?.let {
            try {
                LocalDate.parse(it)
            } catch (e: Exception) {
                reject("$label 的 $key 不是有效日期")
            }
        }

    private fun parseInstant(value: String, label: String): Instant = try {
        Instant.parse(value)
    } catch (e: Exception) {
        reject("$label 不是有效时间")
    }

    private fun JSONObject.requiredString(key: String, label: String): String {
        val value = optionalString(key) ?: reject("$label 缺少 $key")
        if (value.isBlank()) reject("$label 的 $key 不能为空")
        return value
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.requiredInt(key: String, label: String): Int =
        optionalInt(key) ?: reject("$label 缺少 $key")

    private fun JSONObject.optionalInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.requiredPositiveInt(key: String, label: String): Int {
        val value = requiredInt(key, label)
        if (value <= 0) reject("$label 的 $key 必须大于零")
        return value
    }

    private fun JSONObject.requiredLong(key: String, label: String): Long =
        optionalLong(key) ?: reject("$label 缺少 $key")

    private fun JSONObject.optionalLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optionalBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key)

    private inline fun <reified T : Enum<T>> enumRequired(name: String?, label: String): T =
        name?.let { value ->
            enumValues<T>().firstOrNull { it.name == value }
        } ?: reject("$label 的值无效")

    private fun reject(message: String): Nothing = throw BackupRejectedException(message)
}
