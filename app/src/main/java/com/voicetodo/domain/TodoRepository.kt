package com.voicetodo.domain

import java.time.Instant

/** 事项持久化接缝：业务层只依赖此接口，真实实现可用 Room/文件；测试可用内存实现。 */
interface TodoRepository {
    /** 全部未删除事项。 */
    suspend fun all(): List<TodoItem>
    /** 保存（新增或覆盖）。 */
    suspend fun save(item: TodoItem)
    /** 原子插入草稿集合；已有身份返回原事项，绝不覆盖或部分保存。 */
    suspend fun insertDrafts(items: List<TodoItem>): List<TodoItem> {
        require(items.size == 1) { "此存储不支持批量原子保存" }
        val item = items.single()
        return listOf(findById(item.id) ?: item.also { save(it) })
    }
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
    suspend fun deliveredAt(itemId: String, reminderId: String, revision: Long): Instant? =
        if (reminderId == itemId) deliveredAt(itemId, revision) else null

    /** Atomically validate, publish once, then record success. A failed publish remains retryable. */
    suspend fun deliverReminder(
        itemId: String,
        revision: Long,
        atMillis: Long,
        now: () -> Instant,
        publish: suspend (TodoItem) -> Boolean,
    ): Boolean = false

    suspend fun deliverReminder(
        itemId: String, reminderId: String, revision: Long, atMillis: Long, now: () -> Instant,
        publish: suspend (TodoItem) -> Boolean,
    ): Boolean = if (reminderId == itemId) deliverReminder(itemId, revision, atMillis, now, publish) else false
}
