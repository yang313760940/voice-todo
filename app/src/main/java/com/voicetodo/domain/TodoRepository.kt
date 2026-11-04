package com.voicetodo.domain

import java.time.Instant

/** 事项持久化接缝：业务层只依赖此接口，真实实现可用 Room/文件；测试可用内存实现。 */
interface TodoRepository {
    /** 全部未删除事项。 */
    suspend fun all(): List<TodoItem>
    /** 保存（新增或覆盖）。 */
    suspend fun save(item: TodoItem)
    /** 按 id 查找。 */
    suspend fun findById(id: String): TodoItem?
    /** 删除。 */
    suspend fun delete(id: String)
    suspend fun incomplete(): List<TodoItem> = all().filter { it.completedAt == null }
        .sortedWith(compareBy<TodoItem> { it.remindAt == null }.thenBy { it.remindAt }
            .thenByDescending { it.createdAt }.thenBy { it.id })
    suspend fun completed(): List<TodoItem> = all().filter { it.completedAt != null }
        .sortedWith(compareByDescending<TodoItem> { it.completedAt }.thenBy { it.id })
    suspend fun deliveredAt(itemId: String, revision: Long): Instant? = null

    /** Atomically validate, publish once, then record success. A failed publish remains retryable. */
    suspend fun deliverReminder(
        itemId: String,
        revision: Long,
        atMillis: Long,
        now: () -> Instant,
        publish: suspend (TodoItem) -> Boolean,
    ): Boolean = false
}
