package com.voicetodo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.domain.BackupManager
import com.voicetodo.domain.BackupPreview
import com.voicetodo.domain.BackupRejectedException
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FocusUseCases
import com.voicetodo.domain.RestoreCopyInfo
import com.voicetodo.domain.TrashedItem
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.settings.AppPreferences
import com.voicetodo.settings.AppPreferencesStore
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PendingBackupImport(
    val label: String,
    val json: String? = null,
    val restoreCopyName: String? = null,
)

data class DataBackupUiState(
    val busy: Boolean = false,
    val preview: BackupPreview? = null,
    val pending: PendingBackupImport? = null,
    val message: String? = null,
    val error: String? = null,
    val restoreCopies: List<RestoreCopyInfo> = emptyList(),
    /** 每次成功替换后递增，界面据此刷新所有事项页面。 */
    val restoreGeneration: Long = 0,
)

data class TrashUiState(
    val items: List<TrashedItem> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** 每次恢复或永久删除后递增，事项页面据此刷新。 */
    val changeGeneration: Long = 0,
)

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val focusSettings: FocusSettings = FocusSettings(),
    val capabilities: ReminderCapabilities? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val dataBackup: DataBackupUiState = DataBackupUiState(),
    val trash: TrashUiState = TrashUiState(),
)

class SettingsViewModel @JvmOverloads constructor(
    app: Application,
    private val preferencesStore: AppPreferencesStore = AppPreferencesStore(app),
    private val focusUseCases: FocusUseCases = (app as VoiceTodoApp).focusUseCases,
    private val capabilitiesProvider: () -> ReminderCapabilities =
        { (app as VoiceTodoApp).scheduler.capabilities() },
    private val backup: BackupManager? = (app as? VoiceTodoApp)?.backupManager,
    private val todoUseCases: TodoUseCases? = (app as? VoiceTodoApp)?.useCases,
) : AndroidViewModel(app) {
    private val operations = Mutex()
    private val _state = MutableStateFlow(
        SettingsUiState(
            preferences = preferencesStore.load(),
            capabilities = runCatching(capabilitiesProvider).getOrNull(),
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refreshFocusSettings()
    }

    fun setTheme(theme: ThemePreference) {
        runCatching { preferencesStore.saveTheme(theme) }
            .onSuccess { current ->
                _state.value = _state.value.copy(preferences = current, message = null)
            }
            .onFailure { error ->
                _state.value = _state.value.copy(message = error.message ?: "外观设置保存失败")
            }
    }

    fun setFontSize(fontSize: FontSizePreference) {
        runCatching { preferencesStore.saveFontSize(fontSize) }
            .onSuccess { current ->
                _state.value = _state.value.copy(preferences = current, message = null)
            }
            .onFailure { error ->
                _state.value = _state.value.copy(message = error.message ?: "字体大小保存失败")
            }
    }

    fun updateFocusSettings(settings: FocusSettings) {
        if (_state.value.busy) return
        val previous = _state.value.focusSettings
        _state.value = _state.value.copy(focusSettings = settings, busy = true, message = null)
        viewModelScope.launch {
            try {
                operations.withLock { focusUseCases.updateSettings(settings) }
                _state.value = _state.value.copy(
                    busy = false,
                    message = "番茄设置已保存",
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    focusSettings = previous,
                    busy = false,
                    message = error.message ?: "番茄设置保存失败",
                )
            }
        }
    }

    fun refreshCapabilities() {
        _state.value = _state.value.copy(
            capabilities = runCatching(capabilitiesProvider).getOrNull(),
        )
    }

    fun refreshTrash() {
        val useCases = todoUseCases ?: return trashError("回收站功能不可用")
        if (_state.value.trash.busy) return
        _state.value = _state.value.copy(
            trash = _state.value.trash.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                val items = useCases.listTrash()
                _state.value = _state.value.copy(
                    trash = _state.value.trash.copy(busy = false, items = items, error = null),
                )
            } catch (error: Exception) {
                trashError("读取回收站失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun restoreTrash(id: String) {
        val useCases = todoUseCases ?: return trashError("回收站功能不可用")
        if (_state.value.trash.busy) return
        _state.value = _state.value.copy(
            trash = _state.value.trash.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                val result = useCases.restoreDeleted(id)
                refreshTrashAfterChange(
                    useCases,
                    message = if (result.pastReminderCleared) {
                        "事项已恢复；旧提醒已过期并清除，请重新安排"
                    } else {
                        "事项已恢复，未来提醒已重新安排"
                    },
                )
            } catch (error: Exception) {
                trashError("恢复失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun permanentlyDeleteTrash(id: String) {
        val useCases = todoUseCases ?: return trashError("回收站功能不可用")
        if (_state.value.trash.busy) return
        _state.value = _state.value.copy(
            trash = _state.value.trash.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                useCases.permanentlyDelete(id)
                refreshTrashAfterChange(useCases, message = "事项已永久删除")
            } catch (error: Exception) {
                trashError("永久删除失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun refreshTrashAfterChange(useCases: TodoUseCases, message: String) {
        val items = useCases.listTrash()
        _state.value = _state.value.copy(
            trash = _state.value.trash.copy(
                busy = false,
                items = items,
                message = message,
                error = null,
                changeGeneration = _state.value.trash.changeGeneration + 1,
            ),
        )
    }

    private fun trashError(message: String) {
        _state.value = _state.value.copy(
            trash = _state.value.trash.copy(busy = false, error = message, message = null),
        )
    }

    fun refreshFocusSettings() {
        viewModelScope.launch {
            runCatching { focusUseCases.snapshot().settings }
                .onSuccess { settings ->
                    _state.value = _state.value.copy(focusSettings = settings)
                }
        }
    }

    fun dataExportFileName(): String =
        backup?.suggestedFileName() ?: "voice-todo-backup.json"

    fun exportTo(uri: Uri) {
        val manager = backup ?: return dataError("备份功能不可用")
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                val export = withContext(Dispatchers.Default) { manager.export() }
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val output = resolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("无法写入所选位置")
                    output.use { it.write(export.json.toByteArray(Charsets.UTF_8)) }
                }
                val counts = export.preview.counts
                _state.value = _state.value.copy(
                    dataBackup = _state.value.dataBackup.copy(
                        busy = false,
                        error = null,
                        message = "已导出 ${counts.itemCount} 个事项、${counts.listCount} 个清单、" +
                            "${counts.focusSessionCount} 条专注记录。",
                    ),
                )
            } catch (error: Exception) {
                dataError("导出失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun previewImport(uri: Uri, label: String) {
        val manager = backup ?: return dataError("备份功能不可用")
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readImport(uri) }
                applyPreview(manager, text, label)
            } catch (error: Exception) {
                rejectPreview(error)
            }
        }
    }

    /** 供文件选择器之外（含测试）直接校验一段备份文本。 */
    fun previewImport(text: String, label: String) {
        val manager = backup ?: return dataError("备份功能不可用")
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                applyPreview(manager, text, label)
            } catch (error: Exception) {
                rejectPreview(error)
            }
        }
    }

    fun previewRestoreCopy(name: String) {
        val manager = backup ?: return dataError("备份功能不可用")
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) { manager.previewRestoreCopy(name) }
                _state.value = _state.value.copy(
                    dataBackup = _state.value.dataBackup.copy(
                        busy = false,
                        pending = PendingBackupImport(
                            label = "恢复副本 $name",
                            restoreCopyName = name,
                        ),
                        preview = preview,
                        message = null,
                        error = null,
                    ),
                )
            } catch (error: Exception) {
                rejectPreview(error)
            }
        }
    }

    fun cancelImport() {
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(
                pending = null,
                preview = null,
                message = null,
                error = null,
            ),
        )
    }

    fun confirmImport() {
        val manager = backup ?: return dataError("备份功能不可用")
        val pending = _state.value.dataBackup.pending ?: return
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(busy = true, message = null, error = null),
        )
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    when {
                        pending.restoreCopyName != null ->
                            manager.replaceRestoreCopy(pending.restoreCopyName)
                        pending.json != null -> manager.replace(pending.json)
                        else -> throw BackupRejectedException("没有可导入的备份内容")
                    }
                }
                val restoredPreferences = preferencesStore.load()
                val restoredFocus = runCatching { focusUseCases.snapshot().settings }
                    .getOrDefault(_state.value.focusSettings)
                val copies = runCatching { manager.restoreCopies() }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    preferences = restoredPreferences,
                    focusSettings = restoredFocus,
                    dataBackup = _state.value.dataBackup.copy(
                        busy = false,
                        pending = null,
                        preview = null,
                        restoreCopies = copies,
                        restoreGeneration = _state.value.dataBackup.restoreGeneration + 1,
                        message = "已用「${pending.label}」替换本机数据，并重建未来提醒。" +
                            "替换前的数据已保存为恢复副本 ${outcome.restoreCopyName}。",
                        error = outcome.warning,
                    ),
                )
            } catch (error: Exception) {
                dataError("导入失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun refreshRestoreCopies() {
        val manager = backup ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.restoreCopies() } }
                .onSuccess { copies ->
                    _state.value = _state.value.copy(
                        dataBackup = _state.value.dataBackup.copy(restoreCopies = copies),
                    )
                }
        }
    }

    private suspend fun applyPreview(manager: BackupManager, text: String, label: String) {
        val preview = withContext(Dispatchers.Default) { manager.preview(text) }
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(
                busy = false,
                pending = PendingBackupImport(label = label, json = text),
                preview = preview,
                message = null,
                error = null,
            ),
        )
    }

    private fun rejectPreview(error: Exception) {
        val reason = error.message ?: "无法读取备份文件"
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(
                busy = false,
                pending = null,
                preview = null,
                message = null,
                error = "无法导入：$reason",
            ),
        )
    }

    private fun dataError(message: String) {
        _state.value = _state.value.copy(
            dataBackup = _state.value.dataBackup.copy(
                busy = false,
                message = null,
                error = message,
            ),
        )
    }

    private fun readImport(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) throw BackupRejectedException("备份文件超过 20 MB")
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: throw IllegalStateException("无法读取所选文件")
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }
}
