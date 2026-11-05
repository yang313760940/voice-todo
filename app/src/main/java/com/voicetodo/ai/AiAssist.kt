package com.voicetodo.ai

import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.ReminderEdit
import com.voicetodo.domain.TodoEdit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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

/** 集合中的一条事项草稿；确认前不持久化，也不请求系统闹钟。 */
data class AiDraft(
    val itemId: String = UUID.randomUUID().toString(),
    val sourceText: String,
    val title: String,
    val plannedDate: String = "",
    val dueAt: String = "",
    val reminders: List<AiReminderDraft> = emptyList(),
    val quadrant: Quadrant = Quadrant.NotImportantNotUrgent,
    /** AI 的原始建议；用户修改 [quadrant] 时保持不变，确认后只作展示。 */
    val suggestedQuadrant: Quadrant? = null,
    val clarification: String? = null,
    val noReminderAcknowledged: Boolean = false,
    val recurrence: AiRecurrenceDraft? = null,
) {
    val hasUsableReminder: Boolean
        get() = reminders.any { it.value.isNotBlank() } ||
            recurrence?.time?.isNotBlank() == true

    /** 时间含糊且没有提醒时，用户必须补充提醒或明确选择不设提醒。 */
    val requiresNoReminderAcknowledgement: Boolean
        get() = !clarification.isNullOrBlank() && !hasUsableReminder && !noReminderAcknowledged

    val recurrenceNeedsTime: Boolean
        get() = recurrence != null && recurrence.time.isBlank()

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

/** AI 给出的重复建议；开始日期在确认时由计划日期或当天确定。 */
data class AiRecurrenceDraft(
    val frequency: RecurrenceFrequency,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDay: String = "",
    val time: String = "",
) {
    fun toRule(startDate: LocalDate): RecurrenceRule {
        val localTime = try {
            LocalTime.parse(time.trim())
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("重复提醒时间格式应为 HH:mm", e)
        }
        val day = monthDay.trim().takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("每月重复请输入整数日期")
        }
        return RecurrenceRule(
            frequency = frequency,
            startDate = startDate,
            weekdays = weekdays,
            dayOfMonth = day,
            reminderTimes = listOf(localTime),
        ).also { it.validate() }
    }
}

interface AiAssistClient {
    /**
     * 仅把 [text] 与日期/时区上下文发送给用户配置的服务。
     * 实现不得自动重试；失败由用户决定是否再次发起。
     */
    suspend fun organize(text: String, context: AiPromptContext, settings: AiSettings): AiDraft

    /** 兼容已有单草稿适配器；在线客户端实现真实多草稿契约。 */
    suspend fun organizeDrafts(
        text: String,
        context: AiPromptContext,
        settings: AiSettings,
    ): List<AiDraft> = listOf(organize(text, context, settings))
}

class AiAssistException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 解析草稿集合；任何无法验证的字段都不会进入正式事项。 */
internal object AiDraftParser {
    fun parse(content: String, sourceText: String, zoneId: ZoneId): AiDraft {
        val drafts = parseDrafts(content, sourceText, zoneId)
        if (drafts.size != 1) {
            throw AiAssistException("AI 返回了多个草稿，请使用批量核对入口")
        }
        return drafts.single()
    }

    fun parseDrafts(content: String, sourceText: String, zoneId: ZoneId): List<AiDraft> {
        val jsonText = extractJson(content)
        val json = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            throw AiAssistException("AI 返回内容不是有效 JSON；文字已保留，可手动创建", e)
        }
        if (!json.has("drafts")) return listOf(parseRecord(json, sourceText, zoneId))
        val records = json.optJSONArray("drafts")
            ?: throw AiAssistException("AI 返回的草稿列表格式无效；文字已保留")
        if (records.length() == 0) {
            throw AiAssistException("AI 没有返回事项草稿；文字已保留")
        }
        return (0 until records.length()).map { index ->
            val record = records.optJSONObject(index)
                ?: throw AiAssistException("AI 返回的草稿格式无效；文字已保留")
            parseRecord(record, sourceText, zoneId)
        }
    }

    private fun parseRecord(json: JSONObject, sourceText: String, zoneId: ZoneId): AiDraft {
        val title = (json.opt("title") as? String)?.trim().orEmpty()
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
        if (json.has("reminders") && !json.isNull("reminders") && json.opt("reminders") !is JSONArray) {
            throw AiAssistException("AI 返回的提醒列表格式无效；文字已保留")
        }
        val reminders = parseReminders(json.optJSONArray("reminders"), zoneId)
        val quadrantText = optionalText(json, "quadrant")
        val quadrant = parseQuadrant(quadrantText)
        val clarification = optionalText(json, "clarification")
        return AiDraft(
            sourceText = sourceText,
            title = title,
            plannedDate = plannedDate,
            dueAt = dueAt,
            reminders = reminders,
            quadrant = quadrant,
            suggestedQuadrant = quadrantText?.let { quadrant },
            clarification = clarification,
            recurrence = parseRecurrence(json),
        )
    }

    private fun parseRecurrence(json: JSONObject): AiRecurrenceDraft? {
        if (!json.has("recurrence") || json.isNull("recurrence")) return null
        try {
            val record = json.getJSONObject("recurrence")
            val frequency = when (record.getString("frequency").uppercase()) {
                "DAILY" -> RecurrenceFrequency.Daily
                "WEEKLY" -> RecurrenceFrequency.Weekly
                "MONTHLY" -> RecurrenceFrequency.Monthly
                else -> throw IllegalArgumentException("未知重复类型")
            }
            val days = record.optJSONArray("weekdays") ?: JSONArray()
            val weekdays = (0 until days.length()).map { index ->
                DayOfWeek.of(days.getInt(index))
            }.toSet()
            val monthDay = optionalText(record, "monthDay").orEmpty()
            val time = optionalText(record, "time").orEmpty()
            val parsed = AiRecurrenceDraft(frequency, weekdays, monthDay, time)
            // 时间缺失时仍允许进入核对页，让用户补填；其余规则先在这里整体验证。
            parsed.copy(time = time.ifBlank { "09:00" }).toRule(LocalDate.of(2000, 1, 1))
            return parsed
        } catch (e: Exception) {
            throw AiAssistException("AI 返回的重复规则无效；文字已保留，可手动填写", e)
        }
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
                        is Number -> raw.toLong().takeIf { raw.toDouble() == it.toDouble() }
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
