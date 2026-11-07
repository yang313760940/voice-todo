package com.voicetodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicetodo.VoiceTodoApp
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.StorageState
import com.voicetodo.domain.Clock
import com.voicetodo.domain.FocusPhase
import com.voicetodo.domain.FocusSessionState
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FocusSnapshot
import com.voicetodo.domain.FocusUseCases
import com.voicetodo.domain.SystemClock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TimerMode { Pomodoro, Stopwatch }

data class PomodoroUiState(
    val snapshot: FocusSnapshot? = null,
    val now: Instant = Instant.EPOCH,
    val busy: Boolean = false,
    val message: String? = null,
    val settingsDraft: FocusSettings? = null,
    val mode: TimerMode = TimerMode.Pomodoro,
    val selectedPhase: FocusPhase = FocusPhase.Focus,
    val stopwatchRunning: Boolean = false,
    val stopwatchElapsedSeconds: Long = 0,
    val stopwatchResumedAt: Instant? = null,
    val showStats: Boolean = false,
)

class PomodoroViewModel @JvmOverloads constructor(
    app: Application,
    private val useCases: FocusUseCases = (app as VoiceTodoApp).focusUseCases,
    private val storage: RoomTodoRepository = (app as VoiceTodoApp).repository,
    private val clock: Clock = SystemClock(),
) : AndroidViewModel(app) {
    private val operations = Mutex()
    private val _state = MutableStateFlow(PomodoroUiState())
    val state: StateFlow<PomodoroUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            storage.state.collect { status ->
                if (status is StorageState.Failed) {
                    _state.value = _state.value.copy(message = status.message)
                }
            }
        }
        refresh()
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                useCases.reconcileEnd()
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                operations.withLock { reload() }
            } catch (_: Exception) {
            }
        }
    }

    fun onSelectMode(mode: TimerMode) {
        _state.value = _state.value.copy(mode = mode)
    }

    fun onSelectPhase(phase: FocusPhase) {
        if (_state.value.snapshot?.session != null) return
        _state.value = _state.value.copy(selectedPhase = phase)
    }

    fun onToggleStats() {
        _state.value = _state.value.copy(showStats = !_state.value.showStats)
    }

    fun onOpenSettings() {
        val snapshot = _state.value.snapshot ?: return
        _state.value = _state.value.copy(settingsDraft = snapshot.settings)
    }

    fun onSettingsValueChange(settings: FocusSettings) {
        _state.value = _state.value.copy(settingsDraft = settings)
    }

    fun onSaveSettings() {
        val settings = _state.value.settingsDraft ?: return
        action {
            useCases.updateSettings(settings)
            _state.value = _state.value.copy(settingsDraft = null, message = "番茄设置已保存")
            reload()
        }
    }

    fun onCancelSettings() {
        _state.value = _state.value.copy(settingsDraft = null)
    }

    fun onStart(phase: FocusPhase = FocusPhase.Focus, endCurrent: Boolean = false) {
        action {
            val result = useCases.startFocus(phase, endCurrent)
            _state.value = _state.value.copy(
                message = when {
                    result.requiresEndingCurrent -> "已有活动会话；请先结束当前会话"
                    result.previousEndedAt != null -> "已结束上一个会话，并开始新会话"
                    else -> when (phase) {
                        FocusPhase.Focus -> "专注开始"
                        FocusPhase.ShortBreak -> "短休息开始"
                        FocusPhase.LongBreak -> "长休息开始"
                    }
                }
            )
            reload()
        }
    }

    fun onPause() = action {
        useCases.pause()
        _state.value = _state.value.copy(message = "已暂停，暂停期间不计时")
        reload()
    }

    fun onResume() = action {
        useCases.resume()
        _state.value = _state.value.copy(message = "已继续")
        reload()
    }

    fun onEndEarly() = action {
        useCases.endEarly()
        _state.value = _state.value.copy(message = "已提前结束，保留实际时长但不计完成数")
        reload()
    }

    fun onStartAfterTimeout() {
        viewModelScope.launch {
            try {
                useCases.reconcileEnd()
                reload()
            } catch (_: Exception) {
            }
        }
    }

    fun onConfirmEnded() = action {
        useCases.confirmEnded()
        _state.value = _state.value.copy(message = "本阶段已完整结束")
        reload()
    }

    fun onStartStopwatch() {
        if (_state.value.stopwatchRunning) return
        _state.value = _state.value.copy(stopwatchRunning = true, stopwatchResumedAt = clock.now())
    }

    fun onPauseStopwatch() {
        val current = _state.value
        val resumedAt = current.stopwatchResumedAt ?: return
        if (!current.stopwatchRunning) return
        val elapsed = secondsBetween(resumedAt, clock.now())
        _state.value = current.copy(
            stopwatchRunning = false,
            stopwatchResumedAt = null,
            stopwatchElapsedSeconds = current.stopwatchElapsedSeconds + elapsed,
        )
    }

    fun onResetStopwatch() {
        _state.value = _state.value.copy(
            stopwatchRunning = false,
            stopwatchResumedAt = null,
            stopwatchElapsedSeconds = 0,
        )
    }

    fun stopwatchSeconds(now: Instant): Long {
        val current = _state.value
        val resumedAt = current.stopwatchResumedAt
        return if (current.stopwatchRunning && resumedAt != null) {
            current.stopwatchElapsedSeconds + secondsBetween(resumedAt, now)
        } else {
            current.stopwatchElapsedSeconds
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { operations.withLock { block() } } catch (e: Exception) {
                _state.value = _state.value.copy(message = e.message ?: "操作失败")
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    private suspend fun reload() {
        val snapshot = useCases.snapshot()
        _state.value = _state.value.copy(snapshot = snapshot, now = clock.now())
    }

    private fun secondsBetween(from: Instant, to: Instant): Long =
        Duration.between(from, to).seconds.coerceAtLeast(0)
}
