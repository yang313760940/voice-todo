package com.voicetodo.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 用户提交的指定时刻或相对截止规则；同一编辑草稿保留同一个身份。 */
data class ReminderEdit(
    val id: String = UUID.randomUUID().toString(),
    val at: Instant? = null,
    val beforeDue: Duration? = null,
)

/** 一个事项的独立提醒；相对规则与实际调度时刻同时保留。 */
data class TodoReminder(
    val id: String,
    val at: Instant,
    val beforeDue: Duration? = null,
    val revision: Long = 0,
    val outcome: ReminderOutcome = ReminderOutcome.NotReady,
    val deliveredAt: Instant? = null,
)
