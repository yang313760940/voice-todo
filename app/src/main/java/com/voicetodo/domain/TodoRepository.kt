package com.voicetodo.domain

import java.time.Instant

/** 事项持久化接缝：业务层只依赖此接口，真实实现可用 Room/文件；测试可用内存实现。 */
interface TodoRepository {
    /** 全部未删除事项。 */
    suspend fun all(): List<TodoItem>
    /** 保存（新增或覆盖）。 */
    suspend fun save(item: TodoItem)
    /** 原子插入一批新身份；已存在身份返回原事项，不覆盖、不部分保存。 */
    suspend fun insertDrafts(items: List<TodoItem>): List<TodoItem> {
        require(items.size == 1) { "此存储不支持批量原子保存" }
        val item = items.single()
        return listOf(findById(item.id) ?: item.also { save(it) })
    }
    /** 按 id 查找。 */
    suspend fun findById(id: String): TodoItem?
    /** 包含回收站事项的按 id 查找；正常业务读取应使用 [findById]。 */
    suspend fun findAnyById(id: String): TodoItem? = findById(id)
    /** 包含回收站事项的全部记录；用于阻止已删除的重复实例被重新生成。 */
    suspend fun allIncludingDeleted(): List<TodoItem> = all()
    /** 回收站事项，按删除时间倒序。 */
    suspend fun trashed(): List<TodoItem> = emptyList()
    /** 删除。 */
    suspend fun delete(id: String)
    /** 原子地永久删除一批事项；撤销本次添加使用，不进入回收站。 */
    suspend fun deleteAll(ids: Collection<String>) {
        ids.forEach { delete(it) }
    }
    suspend fun incomplete(): List<TodoItem> = all().filter { it.completedAt == null }
        .sortedWith(compareBy<TodoItem> { it.remindAt == null }.thenBy { it.remindAt }
            .thenByDescending { it.createdAt }.thenBy { it.id })
    suspend fun completed(): List<TodoItem> = all().filter { it.completedAt != null }
        .sortedWith(compareByDescending<TodoItem> { it.completedAt }.thenBy { it.id })
    suspend fun deliveredAt(itemId: String, revision: Long): Instant? = null
    suspend fun deliveredAt(itemId: String, reminderId: String, revision: Long): Instant? =
        if (reminderId == itemId) deliveredAt(itemId, revision) else null

    /** 用户自建清单；收集箱没有实体。 */
    suspend fun lists(): List<TodoList> = emptyList()
    suspend fun findListById(id: String): TodoList? = lists().firstOrNull { it.id == id }
    suspend fun saveList(list: TodoList) {}

    /** 删除清单并把其事项移回收集箱；实现应保证这一步不可分割。 */
    suspend fun deleteList(id: String) {
        all().filter { it.listId == id }.forEach { save(it.copy(listId = null)) }
    }

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
