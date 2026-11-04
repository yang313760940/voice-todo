package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 一条待办事项（本切片只支持单条、非重复）。
 *
 * 术语见 GLOSSARY.md：事项与提醒相互独立；事项可以不设置提醒，计划日期、截止时间与提醒也相互独立。
 */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    /** 可编辑标题（来自识别文字，用户可改）。 */
    val title: String,
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
)

/** 逾期只由截止时间、完成状态和调用方提供的当前时刻决定。 */
fun TodoItem.isOverdue(now: Instant): Boolean =
    completedAt == null && dueAt != null && now.isAfter(dueAt)

/** 只有当前修订、当前提醒时刻且未完成的事项才允许投递。 */
fun TodoItem.isCurrentReminder(
    itemId: String,
    revision: Long,
    atMillis: Long,
): Boolean =
    id == itemId &&
        completedAt == null &&
        reminderRevision == revision &&
        remindAt?.toEpochMilli() == atMillis
