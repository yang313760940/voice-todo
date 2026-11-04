package com.voicetodo.domain

import java.time.LocalDateTime
import java.util.UUID

/**
 * 一条待办事项（本切片只支持单条、非重复、指定时刻闹钟）。
 *
 * 术语见 GLOSSARY.md：事项与提醒相互独立；本切片为最小闭环，事项在保存时即绑定一个定时提醒。
 */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    /** 可编辑标题（来自识别文字，用户可改）。 */
    val title: String,
    /** 提醒时刻（本地时间）；本切片必填，一次性，非重复。 */
    val remindAt: LocalDateTime,
    /** 创建时刻（用于列表排序/审计）。 */
    val createdAt: LocalDateTime,
)
