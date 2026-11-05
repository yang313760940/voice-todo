package com.voicetodo.domain

/** 内存仓库，模拟“重开可见”。 */
class InMemoryTodoRepository : TodoRepository {
    private val items = LinkedHashMap<String, TodoItem>()
    private val todoLists = LinkedHashMap<String, TodoList>()
    override suspend fun all(): List<TodoItem> = items.values.filter { it.deletedAt == null }
    override suspend fun save(item: TodoItem) { items[item.id] = item }
    override suspend fun findById(id: String): TodoItem? = items[id]?.takeIf { it.deletedAt == null }
    override suspend fun findAnyById(id: String): TodoItem? = items[id]
    override suspend fun allIncludingDeleted(): List<TodoItem> = items.values.toList()
    override suspend fun trashed(): List<TodoItem> =
        items.values.filter { it.deletedAt != null }
            .sortedWith(compareByDescending<TodoItem> { it.deletedAt }.thenBy { it.id })
    override suspend fun delete(id: String) { items.remove(id) }
    override suspend fun lists(): List<TodoList> =
        todoLists.values.sortedWith(compareBy<TodoList> { it.createdAt }.thenBy { it.id })
    override suspend fun findListById(id: String): TodoList? = todoLists[id]
    override suspend fun saveList(list: TodoList) { todoLists[list.id] = list }
    override suspend fun deleteList(id: String) {
        items.replaceAll { _, item -> if (item.listId == id) item.copy(listId = null) else item }
        todoLists.remove(id)
    }
}
