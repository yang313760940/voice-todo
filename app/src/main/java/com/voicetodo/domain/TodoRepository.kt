package com.voicetodo.domain

/** 事项持久化接缝：业务层只依赖此接口，真实实现可用 Room/文件；测试可用内存实现。 */
interface TodoRepository {
    /** 全部未删除事项，按 remindAt 升序。 */
    fun all(): List<TodoItem>
    /** 保存（新增或覆盖）。 */
    fun save(item: TodoItem)
    /** 按 id 查找。 */
    fun findById(id: String): TodoItem?
    /** 删除。 */
    fun delete(id: String)
}
