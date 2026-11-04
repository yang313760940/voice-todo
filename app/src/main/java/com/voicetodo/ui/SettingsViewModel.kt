package com.voicetodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FocusUseCases
import com.voicetodo.settings.AppPreferences
import com.voicetodo.settings.AppPreferencesStore
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val focusSettings: FocusSettings = FocusSettings(),
    val capabilities: ReminderCapabilities? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel @JvmOverloads constructor(
    app: Application,
    private val preferencesStore: AppPreferencesStore = AppPreferencesStore(app),
    private val focusUseCases: FocusUseCases = (app as VoiceTodoApp).focusUseCases,
    private val capabilitiesProvider: () -> ReminderCapabilities =
        { (app as VoiceTodoApp).scheduler.capabilities() },
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

    fun refreshFocusSettings() {
        viewModelScope.launch {
            runCatching { focusUseCases.snapshot().settings }
                .onSuccess { settings ->
                    _state.value = _state.value.copy(focusSettings = settings)
                }
        }
    }
}
