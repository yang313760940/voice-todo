package com.voicetodo.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** 当地提醒时间的唯一换算规则：跳时后移，重复时刻取较早偏移。 */
fun localReminderInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
    val local = LocalDateTime.of(date, time)
    val rules = zone.rules
    val offsets = rules.getValidOffsets(local)
    return if (offsets.isEmpty()) {
        rules.getTransition(local)?.instant ?: local.atZone(zone).toInstant()
    } else {
        local.toInstant(offsets.maxByOrNull { it.totalSeconds }!!)
    }
}

enum class RecurrenceFrequency {
    Daily,
    Weekly,
    Monthly,
}

/**
 * 重复安排只保存当地日历规则和当地时刻；具体实例另行保存精确 [Instant]。
 * 与一次性提醒分开，避免时区/夏令时变化时改写已经安排的实际时刻。
 */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val startDate: LocalDate,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
    val reminderTimes: List<LocalTime> = listOf(LocalTime.of(9, 0)),
) {
    fun validate() {
        require(reminderTimes.isNotEmpty()) { "重复事项至少需要一个提醒时刻" }
        require(reminderTimes.distinct().size == reminderTimes.size) { "重复提醒时刻不能重复" }
        when (frequency) {
            RecurrenceFrequency.Daily -> Unit
            RecurrenceFrequency.Weekly ->
                require(weekdays.isNotEmpty()) { "每周重复至少选择一个星期" }
            RecurrenceFrequency.Monthly ->
                require(dayOfMonth != null && dayOfMonth in 1..31) { "每月日期需在 1 至 31 之间" }
        }
    }

    /** 第一条不早于 [date] 的当地日期；每月短月使用当月最后一天。 */
    fun firstOccurrenceOnOrAfter(date: LocalDate): LocalDate {
        validate()
        var candidate = maxOf(date, startDate)
        return when (frequency) {
            RecurrenceFrequency.Daily -> candidate
            RecurrenceFrequency.Weekly -> {
                repeat(7) {
                    if (candidate.dayOfWeek in weekdays) return candidate
                    candidate = candidate.plusDays(1)
                }
                throw IllegalStateException("每周规则没有可选星期")
            }
            RecurrenceFrequency.Monthly -> {
                val target = requireNotNull(dayOfMonth)
                fun inMonth(month: YearMonth): LocalDate =
                    month.atDay(minOf(target, month.lengthOfMonth()))
                var occurrence = inMonth(YearMonth.from(candidate))
                if (occurrence.isBefore(candidate)) {
                    occurrence = inMonth(YearMonth.from(candidate).plusMonths(1))
                }
                occurrence
            }
        }
    }

    fun nextOccurrenceAfter(date: LocalDate): LocalDate = firstOccurrenceOnOrAfter(date.plusDays(1))

    fun occurrenceId(seriesId: String, date: LocalDate): String = "$seriesId@$date"

    fun reminderId(seriesId: String, date: LocalDate, index: Int): String =
        "${occurrenceId(seriesId, date)}:r$index"

    /**
     * 从当地日期和时刻解析出实际投递时刻。
     * 夏令时跳时：使用跳时后的首个有效时刻；重复时刻：只取较早偏移，保证一个当地时刻只有一个实例。
     */
    fun instantOn(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        return localReminderInstant(date, time, zone)
    }
}
