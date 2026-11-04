package com.voicetodo.ai

import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.ReminderEdit
import com.voicetodo.domain.TodoEdit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class AiReminderKind { Specified, Relative }

/** AI 返回的可编辑提醒项；确认前只存在于界面状态中。 */
data class AiReminderDraft(
    val id: String = UUID.randomUUID().toString(),
    val kind: AiReminderKind,
    val value: String = "",
)

/**
 * 单事项整理草稿。它不是正式事项，确认前不持久化、也不请求系统闹钟。
 */
data class AiDraft(
    val itemId: String = UUID.randomUUID().toString(),
    val sourceText: String,
    val title: String,
    val plannedDate: String = "",
    val dueAt: String = "",
    val reminders: List<AiReminderDraft> = emptyList(),
    val quadrant: Quadrant = Quadrant.NotImportantNotUrgent,
    val clarification: String? = null,
    val noReminderAcknowledged: Boolean = false,
) {
    val hasUsableReminder: Boolean
        get() = reminders.any { it.value.isNotBlank() }

    /** 时间含糊且没有提醒时，用户必须补充提醒或明确选择不设提醒。 */
    val requiresNoReminderAcknowledgement: Boolean
        get() = !clarification.isNullOrBlank() && !hasUsableReminder && !noReminderAcknowledged

    fun toTodoEdit(zoneId: ZoneId): TodoEdit {
        val due = dueAt.parseDraftInstant(zoneId, "截止时间")
        val planned = plannedDate.trim().takeIf { it.isNotEmpty() }?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("计划日期格式应为 yyyy-MM-dd", e)
            }
        }
        val reminderEdits = reminders.mapNotNull { reminder ->
            val value = reminder.value.trim()
            if (value.isEmpty()) return@mapNotNull null
            when (reminder.kind) {
                AiReminderKind.Specified -> ReminderEdit(
                    id = reminder.id,
                    at = value.parseDraftInstant(zoneId, "提醒时间"),
                )
                AiReminderKind.Relative -> {
                    val minutes = value.toLongOrNull()
                        ?: throw IllegalArgumentException("相对提醒请输入整数分钟")
                    require(due != null) { "相对提醒需要截止时间" }
                    ReminderEdit(id = reminder.id, beforeDue = java.time.Duration.ofMinutes(minutes))
                }
            }
        }
        return TodoEdit(
            title = title,
            plannedDate = planned,
            dueAt = due,
            remindAt = reminderEdits.firstOrNull { it.at != null }?.at,
            reminders = reminderEdits,
        )
    }
}

interface AiAssistClient {
    /**
     * 仅把 [text] 与日期/时区上下文发送给用户配置的服务。
     * 实现不得自动重试；失败由用户决定是否再次发起。
     */
    suspend fun organize(text: String, context: AiPromptContext, settings: AiSettings): AiDraft
}

class AiAssistException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 解析模型返回的单个 JSON 草稿；任何无法验证的字段都不会进入正式事项。 */
internal object AiDraftParser {
    fun parse(content: String, sourceText: String, zoneId: ZoneId): AiDraft {
        val jsonText = extractJson(content)
        val json = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            throw AiAssistException("AI 返回内容不是有效 JSON；文字已保留，可手动创建", e)
        }
        val title = json.optString("title").trim()
        if (title.isEmpty()) {
            throw AiAssistException("AI 返回的草稿缺少事项名称；文字已保留，可手动创建")
        }
        val plannedDate = optionalText(json, "plannedDate")?.let {
            try {
                LocalDate.parse(it).toString()
            } catch (e: DateTimeParseException) {
                throw AiAssistException("AI 返回的计划日期无法识别；文字已保留，可手动填写", e)
            }
        }.orEmpty()
        val dueAt = optionalText(json, "dueAt")?.let {
            formatLocal(parseDateTime(it, zoneId, "截止时间"), zoneId)
        }.orEmpty()
        val reminders = parseReminders(json.optJSONArray("reminders"), zoneId)
        val quadrant = parseQuadrant(optionalText(json, "quadrant"))
        val clarification = optionalText(json, "clarification")
        return AiDraft(
            sourceText = sourceText,
            title = title,
            plannedDate = plannedDate,
            dueAt = dueAt,
            reminders = reminders,
            quadrant = quadrant,
            clarification = clarification,
        )
    }

    private fun parseReminders(array: JSONArray?, zoneId: ZoneId): List<AiReminderDraft> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            val record = array.optJSONObject(index)
                ?: throw AiAssistException("AI 返回的提醒格式无效；文字已保留，可手动填写")
            when (record.optString("kind").uppercase()) {
                "SPECIFIED" -> {
                    val at = optionalText(record, "at")
                        ?: throw AiAssistException("AI 返回的指定提醒缺少时间；请手动填写")
                    AiReminderDraft(
                        kind = AiReminderKind.Specified,
                        value = formatLocal(parseDateTime(at, zoneId, "提醒时间"), zoneId),
                    )
                }
                "RELATIVE" -> {
                    val minutes = when (val raw = record.opt("minutesBeforeDue")) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull()
                        else -> null
                    }
                    if (minutes == null || minutes <= 0) {
                        throw AiAssistException("AI 返回的相对提醒时长无效；请手动填写")
                    }
                    AiReminderDraft(kind = AiReminderKind.Relative, value = minutes.toString())
                }
                else -> throw AiAssistException("AI 返回了未知的提醒类型；请手动填写")
            }
        }
    }

    private fun parseQuadrant(value: String?): Quadrant = when (value?.uppercase()) {
        "IMPORTANT_URGENT" -> Quadrant.ImportantUrgent
        "IMPORTANT_NOT_URGENT" -> Quadrant.ImportantNotUrgent
        "NOT_IMPORTANT_URGENT" -> Quadrant.NotImportantUrgent
        else -> Quadrant.NotImportantNotUrgent
    }

    private fun optionalText(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun extractJson(content: String): String {
        val trimmed = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw AiAssistException("AI 返回内容缺少 JSON 对象；文字已保留，可手动创建")
        }
        return trimmed.substring(start, end + 1)
    }
}

internal fun parseDateTime(value: String, zoneId: ZoneId, field: String): Instant {
    val text = value.trim()
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()?.let { return it }
    val local = text.replace('T', ' ')
    return try {
        LocalDateTime.parse(local, DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .toFormatter())
            .atZone(zoneId).toInstant()
    } catch (e: DateTimeParseException) {
        throw AiAssistException("$field 格式应为 yyyy-MM-dd HH:mm；请手动填写", e)
    }
}

internal fun formatLocal(instant: Instant, zoneId: ZoneId): String =
    LocalDateTime.ofInstant(instant, zoneId)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun String.parseDraftInstant(zoneId: ZoneId, field: String): Instant? {
    val text = trim()
    if (text.isEmpty()) return null
    return try {
        parseDateTime(text, zoneId, field)
    } catch (e: AiAssistException) {
        throw IllegalArgumentException(e.message ?: "$field 格式无效", e)
    }
}
