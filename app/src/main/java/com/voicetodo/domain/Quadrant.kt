package com.voicetodo.domain

import java.time.Duration
import java.time.Instant

/** 四象限分类；由用户最终决定，AI 只能给出建议。 */
enum class Quadrant(val label: String) {
    ImportantUrgent("重要且紧急"),
    ImportantNotUrgent("重要不紧急"),
    NotImportantUrgent("不重要但紧急"),
    NotImportantNotUrgent("不重要不紧急"),
}

/**
 * “临近”窗口：距离截止或下一次提醒不超过 24 小时。
 * 该窗口只用于提示与排序，绝不自动改变事项的象限。
 */
val NEAR_WINDOW: Duration = Duration.ofHours(24)

/** 象限内排序分组：逾期最前，其次临近，最后其他。 */
enum class QuadrantUrgency { Overdue, Near, Normal }

data class QuadrantEntry(val item: TodoItem, val urgency: QuadrantUrgency)

fun TodoItem.quadrantUrgency(now: Instant): QuadrantUrgency {
    if (isOverdue(now)) return QuadrantUrgency.Overdue
    val next = nextQuadrantInstant(now) ?: return QuadrantUrgency.Normal
    return if (!next.isAfter(now.plus(NEAR_WINDOW))) QuadrantUrgency.Near else QuadrantUrgency.Normal
}

/**
 * 下一次仍然有效的截止或提醒时刻。已经过去的安排不产生“临近”提示，
 * 逾期只由截止时间决定。
 */
fun TodoItem.nextQuadrantInstant(now: Instant): Instant? {
    val due = dueAt?.takeIf { !it.isBefore(now) }
    val reminder = reminderTimes().filter { !it.isBefore(now) }.minOrNull()
    return listOfNotNull(due, reminder).minOrNull()
}

fun TodoItem.hasNearDeadline(now: Instant): Boolean =
    completedAt == null && dueAt != null && !dueAt.isBefore(now) &&
        !dueAt.isAfter(now.plus(NEAR_WINDOW))

fun TodoItem.hasNearReminder(now: Instant): Boolean =
    completedAt == null && reminderTimes().any { !it.isBefore(now) && !it.isAfter(now.plus(NEAR_WINDOW)) }

/**
 * 单个象限内的事项：逾期在前，其次临近，最后其他。
 * 同组按最近的有效时刻排序；没有时刻的按创建时间倒序，保证结果稳定。
 */
fun quadrantEntries(items: List<TodoItem>, quadrant: Quadrant, now: Instant): List<QuadrantEntry> =
    items.filter { it.completedAt == null && it.quadrant == quadrant }
        .map { QuadrantEntry(it, it.quadrantUrgency(now)) }
        .sortedWith(
            compareBy<QuadrantEntry> { it.urgency.ordinal }
                .thenBy { it.item.sortInstant(now) ?: Instant.MAX }
                .thenByDescending { it.item.createdAt }
                .thenBy { it.item.id },
        )

private fun TodoItem.sortInstant(now: Instant): Instant? =
    if (isOverdue(now)) dueAt else nextQuadrantInstant(now)
