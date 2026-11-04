package com.voicetodo.domain

enum class RecurrenceFrequency { Daily, Weekly, Monthly }

/** 用户确认的日历重复建议。实例展开和后续调度由重复事项用例负责。 */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val weekdays: Set<Int> = emptySet(),
    val monthDay: Int? = null,
) {
    fun validate(): RecurrenceRule {
        when (frequency) {
            RecurrenceFrequency.Daily -> require(weekdays.isEmpty() && monthDay == null) {
                "每天重复不应包含星期或月日期"
            }
            RecurrenceFrequency.Weekly -> require(weekdays.isNotEmpty() &&
                weekdays.all { it in 1..7 } && monthDay == null) { "每周重复请选择星期一至星期日" }
            RecurrenceFrequency.Monthly -> require(monthDay in 1..31 && weekdays.isEmpty()) {
                "每月重复请输入 1 至 31 日"
            }
        }
        return this
    }
}
