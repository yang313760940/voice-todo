package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 一条待办事项或重复安排中的一个实例。
 *
 * 术语见 GLOSSARY.md：事项与提醒相互独立；重复安排本身是模板（[recurrence]），
 * 每个实例通过 [seriesId] 与 [occurrenceDate] 保持稳定身份并独立完成。
 */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    /** 可编辑标题（来自识别文字，用户可改）。 */
    val title: String,
    /** 独立备注：地点、材料等细节；为空表示没有备注，不参与提醒正文。 */
    val note: String = "",
    /** 提醒时刻；为空表示该事项当前没有提醒。 */
    val remindAt: Instant? = null,
    /** 计划日期；只表达用户计划，本身不安排闹钟。 */
    val plannedDate: LocalDate? = null,
    /** 截止时间；为空不产生逾期状态。 */
    val dueAt: Instant? = null,
    /** 完成时刻；为空表示未完成。 */
    val completedAt: Instant? = null,
    /** 创建时刻（用于列表排序/审计）。 */
    val createdAt: Instant,
    /** 提醒修订号；每次完成或恢复都会推进，使旧系统闹钟失效。 */
    val reminderRevision: Long = 0,
    /** 多提醒详情的完整安排；旧的单提醒字段保留用于兼容原有入口。 */
    val reminders: List<TodoReminder> = emptyList(),
    /** 四象限分类；手动创建默认“不重要不紧急”，AI 建议可在确认前修改。 */
    val quadrant: Quadrant = Quadrant.NotImportantNotUrgent,
    /** AI 给出的初始建议；只用于展示，不随用户移动象限而改变。 */
    val suggestedQuadrant: Quadrant? = null,
    /** 所属自建清单；为空表示收集箱。 */
    val listId: String? = null,
    /** 实例所属重复安排；为空表示普通事项或重复模板。 */
    val seriesId: String? = null,
    /** 实例对应的当地日期；与 [seriesId] 一起构成稳定身份。 */
    val occurrenceDate: LocalDate? = null,
    /** 重复模板的规则；实例自身为空，避免每次实例重复保存规则。 */
    val recurrence: RecurrenceRule? = null,
    /** 用户选择默认在通知入口完成、延后或关闭本次提醒时，该实例是否使用持续响铃通知；不影响事项本身。 */
    val persistentRing: Boolean = false,
    /** 到点是否用本机语音朗读标题；只影响提示呈现，不影响事项与提醒调度。 */
    val readAloud: Boolean = false,
    /** 锁屏时是否仍朗读标题；默认关闭，避免私人内容在锁屏状态被读出。 */
    val readAloudWhenLocked: Boolean = false,
    /**
     * 本次提醒被“关闭”或被“延后”取代后记下的已处理时刻，使旧闹钟投递失效。
     * 完成事项不改它；它与提醒修订号、提醒时刻一起判定当前提醒是否仍有效。
     */
    val dismissedAt: Instant? = null,
    /** 进入回收站的时刻；为空表示正常事项。删除时立即撤销全部未来提醒。 */
    val deletedAt: Instant? = null,
)

/** 逾期只由截止时间、完成状态和调用方提供的当前时刻决定。 */
fun TodoItem.isOverdue(now: Instant): Boolean =
    completedAt == null && dueAt != null && now.isAfter(dueAt)

/** 只有当前修订、当前提醒时刻且未完成的事项才允许投递。 */
fun TodoItem.isCurrentReminder(
    itemId: String,
    revision: Long,
    atMillis: Long,
): Boolean = isCurrentReminder(itemId, itemId, revision, atMillis)

fun TodoItem.findReminder(reminderId: String): TodoReminder? =
    if (reminders.isNotEmpty()) reminders.firstOrNull { it.id == reminderId }
    else if (reminderId == id) remindAt?.let { TodoReminder(id, it, revision = reminderRevision) }
    else null

/** 当前生效的全部提醒时刻；旧数据只有 remindAt 时按单条提醒处理。 */
fun TodoItem.reminderTimes(): List<Instant> =
    if (reminders.isEmpty()) listOfNotNull(remindAt) else reminders.map { it.at }

fun TodoItem.isCurrentReminder(itemId: String, reminderId: String, revision: Long, atMillis: Long): Boolean {
    val reminder = findReminder(reminderId) ?: return false
    return id == itemId && completedAt == null && reminder.revision == revision &&
        reminder.at.toEpochMilli() == atMillis
}
