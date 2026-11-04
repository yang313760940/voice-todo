package com.voicetodo.domain

/** 内存仓库，模拟“重开可见”。 */
class InMemoryTodoRepository : TodoRepository {
    private val items = LinkedHashMap<String, TodoItem>()
    override suspend fun all(): List<TodoItem> = items.values.toList()
    override suspend fun save(item: TodoItem) { items[item.id] = item }
    override suspend fun findById(id: String): TodoItem? = items[id]
    override suspend fun delete(id: String) { items.remove(id) }
}
