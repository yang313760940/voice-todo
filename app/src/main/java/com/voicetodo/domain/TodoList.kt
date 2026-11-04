package com.voicetodo.domain

import java.time.Instant
import java.util.UUID

/** 用户自建清单；收集箱没有实体，用事项的 listId == null 表达。 */
data class TodoList(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Instant,
)
