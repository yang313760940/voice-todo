package com.voicetodo.data

import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** 数据文件存在但无法可靠解析时抛出，避免调用方把损坏文件误当成空列表。 */
class TodoStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Legacy JSON migration reader. Production storage is RoomTodoRepository.
 * The write API remains only for legacy compatibility fixtures; migration never changes this file.
 */
class FileTodoRepository(
    private val baseDir: File,
    private val zoneId: ZoneId,
) : TodoRepository {

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
                    remindAt = o.optionalString("remindAt")?.let(::parseInstant),
                    plannedDate = o.optionalString("plannedDate")?.let(LocalDate::parse),
                    dueAt = o.optionalString("dueAt")?.let(::parseInstant),
                    completedAt = o.optionalString("completedAt")?.let(::parseInstant),
                    createdAt = parseInstant(o.getString("createdAt")),
                    reminderRevision = if (o.has("reminderRevision") && !o.isNull("reminderRevision")) {
                        o.getLong("reminderRevision")
                    } else {
                        0L
                    },
                )
            }
            list
        } catch (e: Exception) {
            throw TodoStorageException("无法读取事项文件 ${file.absolutePath}", e)
        }
    }

    private fun parseInstant(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            LocalDateTime.parse(value).atZone(zoneId).toInstant()
        }

    private fun writeAll(items: List<TodoItem>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    it.remindAt?.let { at -> put("remindAt", at.toString()) }
                    it.plannedDate?.let { date -> put("plannedDate", date.toString()) }
                    it.dueAt?.let { at -> put("dueAt", at.toString()) }
                    it.completedAt?.let { at -> put("completedAt", at.toString()) }
                    put("createdAt", it.createdAt.toString())
                    put("reminderRevision", it.reminderRevision)
                }
            )
        }
        baseDir.mkdirs()
        file.writeText(arr.toString())
    }

    override suspend fun all(): List<TodoItem> = synchronized(lock) {
        readAll()
    }

    override suspend fun save(item: TodoItem) = synchronized(lock) {
        val items = readAll()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items += item
        writeAll(items)
    }

    override suspend fun findById(id: String): TodoItem? = synchronized(lock) {
        readAll().firstOrNull { it.id == id }
    }

    override suspend fun delete(id: String) = synchronized(lock) {
        val items = readAll()
        items.removeAll { it.id == id }
        writeAll(items)
    }
}

private fun JSONObject.optionalString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
