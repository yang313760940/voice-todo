package com.voicetodo.data

import androidx.room.withTransaction
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoList
import com.voicetodo.domain.TodoRepository
import com.voicetodo.domain.isCurrentReminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

sealed interface StorageState {
    data object Loading : StorageState
    data object Ready : StorageState
    data class Failed(val message: String) : StorageState
}

class RoomTodoRepository(
    private val db: TodoDatabase,
    private val legacy: FileTodoRepository,
) : TodoRepository {
    private val migrationLock = Mutex()
    private val mutableState = MutableStateFlow<StorageState>(StorageState.Loading)
    val state = mutableState.asStateFlow()
    private val dao get() = db.todos()

    suspend fun initialize() = migrationLock.withLock {
        if (mutableState.value == StorageState.Ready) return@withLock
        mutableState.value = StorageState.Loading
        try {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    if (dao.migrated(LEGACY_MIGRATION) == 0) {
                        val items = legacy.all()
                        require(items.map { it.id }.distinct().size == items.size) { "旧文件含重复事项 id" }
                        dao.importItems(items.map(TodoEntity::from))
                        dao.migrated(StorageMigration(LEGACY_MIGRATION))
                    }
                }
            }
            mutableState.value = StorageState.Ready
        } catch (e: Exception) {
            mutableState.value = StorageState.Failed("数据迁移失败，旧文件已保留；修复后可重试")
            throw TodoStorageException("数据迁移失败，旧文件已保留", e)
        }
    }

    private suspend fun <T> ready(block: suspend () -> T): T {
        initialize()
        return withContext(Dispatchers.IO) { block() }
    }

    override suspend fun all() = ready { dao.all().map { it.item() } }
    override suspend fun incomplete() = ready { dao.incomplete().map { it.item() } }
    override suspend fun completed() = ready { dao.completed().map { it.item() } }
    override suspend fun findById(id: String) = ready { dao.find(id)?.item() }
    override suspend fun save(item: TodoItem) = ready { dao.save(TodoEntity.from(item)) }
    override suspend fun lists(): List<TodoList> = ready { dao.lists().map { it.list() } }
    override suspend fun findListById(id: String) = ready { dao.findList(id)?.list() }
    override suspend fun saveList(list: TodoList) = ready { dao.saveList(TodoListEntity.from(list)) }
    override suspend fun deleteList(id: String) = ready {
        db.withTransaction {
            dao.clearList(id)
            dao.deleteList(id)
        }
    }
    override suspend fun delete(id: String) = ready {
        db.withTransaction { dao.deleteDeliveries(id); dao.delete(id) }
    }
    private suspend fun deliveryTime(itemId: String, reminderId: String, revision: Long): Instant? =
        dao.reminderDeliveredAt(itemId, reminderId, revision) ?:
            if (reminderId == itemId) dao.deliveredAt(itemId, revision) else null

    override suspend fun deliveredAt(itemId: String, revision: Long) = ready { deliveryTime(itemId, itemId, revision) }
    override suspend fun deliveredAt(itemId: String, reminderId: String, revision: Long) =
        ready { deliveryTime(itemId, reminderId, revision) }

    override suspend fun deliverReminder(
        itemId: String, revision: Long, atMillis: Long, now: () -> Instant,
        publish: suspend (TodoItem) -> Boolean,
    ): Boolean = deliverReminder(itemId, itemId, revision, atMillis, now, publish)

    override suspend fun deliverReminder(
        itemId: String, reminderId: String, revision: Long, atMillis: Long, now: () -> Instant,
        publish: suspend (TodoItem) -> Boolean,
    ): Boolean = ready {
        // The transaction serializes edits/completion against the final validation and success record.
        db.withTransaction {
            val item = dao.find(itemId)?.item() ?: return@withTransaction false
            if (!item.isCurrentReminder(itemId, reminderId, revision, atMillis) ||
                deliveryTime(itemId, reminderId, revision) != null) return@withTransaction false
            if (!publish(item)) return@withTransaction false
            val deliveredAt = now()
            dao.delivered(ReminderDeliveryEntity(itemId, reminderId, revision, deliveredAt))
            if (item.reminders.isNotEmpty()) dao.save(TodoEntity.from(item.copy(reminders = item.reminders.map {
                if (it.id == reminderId) it.copy(deliveredAt = deliveredAt) else it
            })))
            true
        }
    }

    companion object { private const val LEGACY_MIGRATION = "todos-json-v1" }
}
