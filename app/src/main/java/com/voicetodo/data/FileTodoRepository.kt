package com.voicetodo.data

import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * 最小持久化实现：单个 JSON 文件，进程重启/重开后仍可见。
 * 首版切片只需一条能力的可靠保存，后续票再评估迁移到 Room。
 */
class FileTodoRepository(private val baseDir: File) : TodoRepository {

    private val file = File(baseDir, "todos.json")
    private val lock = Any()

    private fun readAll(): MutableList<TodoItem> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<TodoItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += TodoItem(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    remindAt = LocalDateTime.parse(o.getString("remindAt")),
                    createdAt = LocalDateTime.parse(o.getString("createdAt")),
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeAll(items: List<TodoItem>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("remindAt", it.remindAt.toString())
                    put("createdAt", it.createdAt.toString())
                }
            )
        }
        baseDir.mkdirs()
        file.writeText(arr.toString())
    }

    override fun all(): List<TodoItem> = synchronized(lock) {
        readAll().sortedBy { it.remindAt }
    }

    override fun save(item: TodoItem) = synchronized(lock) {
        val items = readAll()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items += item
        writeAll(items)
    }

    override fun findById(id: String): TodoItem? = synchronized(lock) {
        readAll().firstOrNull { it.id == id }
    }

    override fun delete(id: String) = synchronized(lock) {
        val items = readAll()
        items.removeAll { it.id == id }
        writeAll(items)
    }
}
