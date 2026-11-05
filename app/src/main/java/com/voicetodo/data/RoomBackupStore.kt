package com.voicetodo.data

import androidx.room.withTransaction
import com.voicetodo.domain.BackupData
import com.voicetodo.domain.BackupStore
import com.voicetodo.domain.FocusSettings
import com.voicetodo.settings.AppPreferences

/**
 * 备份快照与整库替换共用同一个 Room 事务。
 * storage_migrations 刻意保留，避免恢复旧备份后再次把遗留 JSON 当成新数据导入。
 */
class RoomBackupStore(
    private val db: TodoDatabase,
    private val repository: RoomTodoRepository,
) : BackupStore {
    override suspend fun snapshot(preferences: AppPreferences): BackupData {
        // 先完成旧文件迁移，避免导出/替换绕过 RoomTodoRepository 的初始化边界。
        repository.initialize()
        return db.withTransaction {
            BackupData(
                items = db.todos().all().map { it.item() },
                lists = db.todos().lists().map { it.list() },
                focusSessions = db.focus().history().map { it.session() },
                focusSettings = db.focus().settings()?.settings() ?: FocusSettings(),
                preferences = preferences,
            )
        }
    }

    override suspend fun replace(data: BackupData) {
        repository.initialize()
        db.withTransaction {
            val todos = db.todos()
            val focus = db.focus()
            todos.clearReminderDeliveries()
            todos.clearDeliveries()
            todos.clearTodos()
            todos.clearLists()
            focus.clearSessions()

            todos.importLists(data.lists.map(TodoListEntity::from))
            todos.importItems(data.items.map(TodoEntity::from))
            focus.importAll(data.focusSessions.map(FocusSessionEntity::from))
            focus.saveSettings(FocusSettingsEntity.from(data.focusSettings))
        }
    }
}
