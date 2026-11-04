package com.voicetodo.domain

import java.time.LocalDateTime

/** 内存仓库，模拟“重开可见”。 */
class InMemoryTodoRepository : TodoRepository {
    private val items = LinkedHashMap<String, TodoItem>()
    override fun all(): List<TodoItem> = items.values.sortedBy { it.remindAt }
    override fun save(item: TodoItem) { items[item.id] = item }
    override fun findById(id: String): TodoItem? = items[id]
    override fun delete(id: String) { items.remove(id) }
}
