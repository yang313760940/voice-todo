package com.voicetodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.domain.TodoItem
import com.voicetodo.speech.OfflineTranscriber
import com.voicetodo.speech.PcmRecorder
import com.voicetodo.speech.SherpaOfflineTranscriber
import com.voicetodo.speech.TranscriptResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 录音状态机。 */
enum class RecordState { Idle, Recording, Transcribing }

data class UiState(
    val items: List<TodoItem> = emptyList(),
    val recordState: RecordState = RecordState.Idle,
    val draftText: String = "",
    val draftHasTime: Boolean = false,
    val draftTime: Instant? = null,
    val message: String? = null,
    val capabilities: ReminderCapabilities? = null,
)

/**
 * 单页待办 ViewModel：录音 → 离线转写 → 可编辑标题 → 手选时间 → 保存/取消。
 * 通过 [VoiceTodoApp.useCases] 统一入口持久化并调度系统闹钟。
 */
class TodoViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app as VoiceTodoApp
    private val transcriber: OfflineTranscriber = SherpaOfflineTranscriber(appContext)
    private val recorder = PcmRecorder()
    private val zoneId: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        _state.value = _state.value.copy(
            capabilities = appContext.scheduler.capabilities(),
            message = if (!transcriber.isReady()) "随包模型缺失" else null,
        )
    }

    private fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(items = appContext.useCases.listItems())
        }
    }

    fun refreshCapabilities() {
        _state.value = _state.value.copy(capabilities = appContext.scheduler.capabilities())
    }

    fun onToggleRecord() {
        when (_state.value.recordState) {
            RecordState.Idle -> {
                if (!recorder.start()) {
                    _state.value = _state.value.copy(message = "麦克风不可用或未授权")
                } else {
                    _state.value = _state.value.copy(recordState = RecordState.Recording, message = null)
                }
            }
            RecordState.Recording -> stopAndTranscribe()
            RecordState.Transcribing -> Unit
        }
    }

    private fun stopAndTranscribe() {
        _state.value = _state.value.copy(recordState = RecordState.Transcribing)
        viewModelScope.launch {
            val samples = recorder.stop()
            if (samples.isEmpty()) {
                _state.value = _state.value.copy(recordState = RecordState.Idle, message = "没有录到音频")
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                transcriber.transcribe(samples, recorder.sampleRate)
            }
            when (result) {
                is TranscriptResult.Success ->
                    _state.value = _state.value.copy(
                        recordState = RecordState.Idle,
                        draftText = result.text,
                        message = null,
                    )
                is TranscriptResult.Failure ->
                    _state.value = _state.value.copy(
                        recordState = RecordState.Idle,
                        message = result.message,
                    )
            }
        }
    }

    fun onDraftTextChange(text: String) {
        _state.value = _state.value.copy(draftText = text)
    }

    /** 用户手选一个未来时间。 */
    fun onPickTime(hour: Int, minute: Int) {
        val now = LocalDateTime.now(zoneId)
        var at = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        if (!at.isAfter(now)) at = at.plusDays(1)
        _state.value = _state.value.copy(draftHasTime = true, draftTime = at.atZone(zoneId).toInstant())
    }

    fun onSave() {
        val s = _state.value
        val at = s.draftTime
        if (!s.draftHasTime || at == null) {
            _state.value = s.copy(message = "请先选择提醒时间")
            return
        }
        viewModelScope.launch {
            try {
                val result = appContext.useCases.confirmDraft(s.draftText, at)
                val message = when (result.outcome) {
                    com.voicetodo.domain.ReminderOutcome.NotRequested -> "事项已保存（未设置提醒）"
                    com.voicetodo.domain.ReminderOutcome.Scheduled -> "已保存并安排提醒"
                    com.voicetodo.domain.ReminderOutcome.NotReady -> "事项已保存，但提醒未就绪（缺精确闹钟权限）"
                }
                _state.value = _state.value.copy(
                    draftText = "", draftHasTime = false, draftTime = null,
                    message = message,
                )
                _state.value = _state.value.copy(items = appContext.useCases.listItems())
            } catch (e: IllegalArgumentException) {
                _state.value = _state.value.copy(message = e.message ?: "保存失败")
            }
        }
    }

    fun onCancel() {
        _state.value = _state.value.copy(
            draftText = "", draftHasTime = false, draftTime = null, message = null,
        )
    }

    fun onDelete(id: String) {
        viewModelScope.launch {
            appContext.useCases.deleteItem(id)
            _state.value = _state.value.copy(items = appContext.useCases.listItems())
        }
    }

    /** 供仪器测试注入固定音频并识别（跳过麦克风）。 */
    fun transcribeSamplesForTest(samples: FloatArray, sampleRate: Int) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { transcriber.transcribe(samples, sampleRate) }
            when (result) {
                is TranscriptResult.Success -> _state.value = _state.value.copy(draftText = result.text, message = null)
                is TranscriptResult.Failure -> _state.value = _state.value.copy(message = result.message)
            }
        }
    }

    override fun onCleared() {
        recorder.release()
        super.onCleared()
    }
}
