package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 待办页可选视图；自建清单用 [CustomList] 表达，收集箱是独立的内置视图。 */
sealed interface TodoView {
    data object Today : TodoView
    data object Inbox : TodoView
    data object RecentSevenDays : TodoView
    data object All : TodoView
    data object Completed : TodoView
    data class CustomList(val listId: String) : TodoView
}

/** 收集箱与自建清单都按 listId 归类，并支持“仅未安排时间”筛选。 */
val TodoView.supportsUnscheduledFilter: Boolean
    get() = this is TodoView.Inbox || this is TodoView.CustomList

/** 计划日期、截止时间或任一提醒落在该当地日期。 */
fun TodoItem.occursOn(date: LocalDate, zone: ZoneId): Boolean =
    plannedDate == date ||
        dueAt?.atZone(zone)?.toLocalDate() == date ||
        reminderTimes().any { it.atZone(zone).toLocalDate() == date }

/** “未安排时间”表示计划日期、截止时间、提醒时间均未设置。 */
fun TodoItem.isUnscheduled(): Boolean =
    plannedDate == null && dueAt == null && reminderTimes().isEmpty()

/**
 * 视图数据只从同一份事项集合推导，避免各页各自维护副本。
 *
 * 今天：当地今天的计划、提醒、截止，加上全部未完成逾期事项，按事项去重。
 * 最近七天：当地今天起连续七个日期（含今天），按日期范围筛选。
 */
fun itemsForView(
    allItems: List<TodoItem>,
    view: TodoView,
    now: Instant,
    zone: ZoneId,
    unscheduledOnly: Boolean = false,
): List<TodoItem> = when (view) {
    TodoView.Today -> todayItems(allItems, now, zone)
    TodoView.Inbox -> listItems(allItems, null, unscheduledOnly)
    is TodoView.CustomList -> listItems(allItems, view.listId, unscheduledOnly)
    TodoView.RecentSevenDays -> recentSevenDaysItems(allItems, now, zone)
    TodoView.All -> allItems.filter { it.completedAt == null }
        .sortedWith(pendingOrder)
    TodoView.Completed -> allItems.filter { it.completedAt != null }
        .sortedWith(compareByDescending<TodoItem> { it.completedAt }.thenBy { it.id })
}

private val pendingOrder = compareBy<TodoItem> { it.remindAt == null }
    .thenBy { it.remindAt }
    .thenByDescending { it.createdAt }
    .thenBy { it.id }

private fun todayItems(items: List<TodoItem>, now: Instant, zone: ZoneId): List<TodoItem> {
    val today = now.atZone(zone).toLocalDate()
    return items
        .filter { it.completedAt == null && (it.isOverdue(now) || it.occursOn(today, zone)) }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<TodoItem> { if (it.isOverdue(now)) 0 else 1 }
                .thenBy { it.todaySortInstant(now, zone) ?: Instant.MAX }
                .thenByDescending { it.createdAt }
                .thenBy { it.id },
        )
}

private fun TodoItem.todaySortInstant(now: Instant, zone: ZoneId): Instant? {
    if (isOverdue(now)) return dueAt
    val today = now.atZone(zone).toLocalDate()
    return (listOfNotNull(dueAt) + reminderTimes())
        .filter { it.atZone(zone).toLocalDate() == today }
        .minOrNull()
}

private fun recentSevenDaysItems(
    items: List<TodoItem>,
    now: Instant,
    zone: ZoneId,
): List<TodoItem> {
    val start = now.atZone(zone).toLocalDate()
    val end = start.plusDays(6)
    return items
        .filter { it.completedAt == null && it.occursBetween(start, end, zone) }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<TodoItem> { it.recentSortInstant(zone) ?: Instant.MAX }
                .thenByDescending { it.createdAt }
                .thenBy { it.id },
        )
}

private fun TodoItem.occursBetween(start: LocalDate, end: LocalDate, zone: ZoneId): Boolean {
    fun LocalDate?.inRange(): Boolean = this != null && !isBefore(start) && !isAfter(end)
    return plannedDate.inRange() ||
        dueAt?.atZone(zone)?.toLocalDate().inRange() ||
        reminderTimes().any { it.atZone(zone).toLocalDate().inRange() }
}

private fun TodoItem.recentSortInstant(zone: ZoneId): Instant? =
    listOfNotNull(
        plannedDate?.atStartOfDay(zone)?.toInstant(),
        dueAt,
    ).plus(reminderTimes()).minOrNull()

private fun listItems(
    items: List<TodoItem>,
    listId: String?,
    unscheduledOnly: Boolean,
): List<TodoItem> = items
    .filter {
        it.completedAt == null && it.listId == listId &&
            (!unscheduledOnly || it.isUnscheduled())
    }
    .sortedWith(pendingOrder)
