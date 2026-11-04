package com.voicetodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.domain.TodoEdit
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.ReminderEdit
import com.voicetodo.domain.Clock
import com.voicetodo.domain.SystemClock
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.StorageState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** 录音状态机。 */
enum class RecordState { Idle, Recording, Transcribing }

enum class DetailField { Plan, Due, Reminder }

enum class ReminderDraftKind { Specified, Relative }

data class ReminderDraft(
    val id: String = UUID.randomUUID().toString(),
    val kind: ReminderDraftKind,
    val value: String = "",
)

data class DetailDraft(
    val original: TodoItem,
    val title: String,
    val plannedDate: String,
    val dueAt: String,
    val remindAt: String,
    val reminders: List<ReminderDraft>,
) {
    fun edit(zone: ZoneId): TodoEdit {
        fun parseTime(text: String, originalTime: Instant?): Instant? {
            if (text == timeText(originalTime, zone)) return originalTime
            return text.trim().takeIf { it.isNotEmpty() }
                ?.let { LocalDateTime.parse(it.replace(' ', 'T')).atZone(zone).toInstant() }
        }
        try {
            val due = parseTime(dueAt, original.dueAt)
            val reminderEdits = reminders.mapNotNull { reminder ->
                val value = reminder.value.trim()
                if (value.isEmpty()) return@mapNotNull null
                when (reminder.kind) {
                    ReminderDraftKind.Specified -> ReminderEdit(reminder.id,
                        at = LocalDateTime.parse(value.replace(' ', 'T')).atZone(zone).toInstant())
                    ReminderDraftKind.Relative -> ReminderEdit(reminder.id,
                        beforeDue = Duration.ofMinutes(value.toLongOrNull()
                            ?: throw IllegalArgumentException("相对提醒请输入整数分钟")))
                }
            }
            return TodoEdit(title, plannedDate.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                due, reminderEdits.firstOrNull()?.at, reminderEdits)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("日期格式：yyyy-MM-dd；时间格式：yyyy-MM-dd HH:mm", e)
        }
    }

    companion object {
        fun from(item: TodoItem, zone: ZoneId): DetailDraft {
            val rules = if (item.reminders.isNotEmpty()) item.reminders.map {
                if (it.beforeDue == null) ReminderDraft(it.id, ReminderDraftKind.Specified, timeText(it.at, zone))
                else ReminderDraft(it.id, ReminderDraftKind.Relative, it.beforeDue.toMinutes().toString())
            } else listOf(ReminderDraft(item.id, ReminderDraftKind.Specified, timeText(item.remindAt, zone)))
            return DetailDraft(item, item.title, item.plannedDate?.toString().orEmpty(),
                timeText(item.dueAt, zone), timeText(item.remindAt, zone), rules)
        }
        fun timeText(at: Instant?, zone: ZoneId): String =
            at?.atZone(zone)?.toLocalDateTime()?.toString()?.replace('T', ' ').orEmpty()
    }
}

data class UiState(
    val items: List<TodoItem> = emptyList(),
    val completedItems: List<TodoItem> = emptyList(),
    val deliveredIds: Set<String> = emptySet(),
    val detail: DetailDraft? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val storageError: String? = null,
    val now: Instant = Instant.EPOCH,
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
class TodoViewModel @JvmOverloads constructor(
    app: Application,
    private val useCases: TodoUseCases = (app as VoiceTodoApp).useCases,
    private val storage: RoomTodoRepository = (app as VoiceTodoApp).repository,
    private val capabilities: () -> ReminderCapabilities = { (app as VoiceTodoApp).scheduler.capabilities() },
    private val transcriber: OfflineTranscriber = SherpaOfflineTranscriber(app),
    private val clock: Clock = SystemClock(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
) : AndroidViewModel(app) {
    private val recorder = PcmRecorder()
    private val operations = Mutex()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(
            capabilities = capabilities(),
            now = clock.now(),
            message = if (!transcriber.isReady()) "随包模型缺失" else null,
        )
        viewModelScope.launch {
            storage.state.collect { status ->
                _state.value = _state.value.copy(loading = when (status) {
                    StorageState.Loading -> true
                    is StorageState.Failed -> false
                    StorageState.Ready -> _state.value.loading
                },
                    storageError = (status as? StorageState.Failed)?.message)
            }
        }
        refresh()
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                _state.value = _state.value.copy(now = clock.now())
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            try { operations.withLock { reload() } } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(message = e.message)
            }
        }
    }

    private suspend fun reload() {
        val pending = useCases.listIncomplete()
        val completed = useCases.listCompleted()
        val delivered = (pending + completed).filter { useCases.wasDelivered(it) }.map { it.id }.toSet()
        _state.value = _state.value.copy(items = pending, completedItems = completed,
            deliveredIds = delivered, now = clock.now(), loading = false)
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { operations.withLock { block() } } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(message = e.message ?: "操作失败")
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    fun onRefresh() {
        refreshCapabilities()
        if (!_state.value.busy) refresh()
    }

    fun onRetryStorage() = action { storage.initialize(); reload() }

    fun refreshCapabilities() {
        _state.value = _state.value.copy(capabilities = capabilities())
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
        val now = LocalDateTime.ofInstant(clock.now(), zoneId)
        var at = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        if (!at.isAfter(now)) at = at.plusDays(1)
        _state.value = _state.value.copy(draftHasTime = true, draftTime = at.atZone(zoneId).toInstant())
    }

    fun onSave() = save(withoutReminder = false)
    fun onSaveWithoutReminder() = save(withoutReminder = true)

    private fun save(withoutReminder: Boolean) {
        val s = _state.value
        val at = if (withoutReminder) null else s.draftTime
        if (!withoutReminder && (!s.draftHasTime || at == null)) {
            _state.value = s.copy(message = "请先选择提醒时间")
            return
        }
        action {
            val result = useCases.confirmDraft(s.draftText, at)
            _state.value = _state.value.copy(
                draftText = "", draftHasTime = false, draftTime = null,
                message = outcomeMessage(result.outcome),
            )
            reload()
        }
    }

    fun onCancel() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            draftText = "", draftHasTime = false, draftTime = null, message = null,
        )
    }

    fun onDelete(id: String) {
        action {
            useCases.deleteItem(id)
            reload()
        }
    }

    fun onOpenDetail(id: String) = action {
        _state.value = _state.value.copy(detail = DetailDraft.from(useCases.findItem(id), zoneId))
    }

    fun onDetailTitleChange(title: String) {
        if (!_state.value.busy) _state.value = _state.value.copy(detail = _state.value.detail?.copy(title = title))
    }

    fun onDetailValueChange(field: DetailField, value: String) {
        val detail = _state.value.detail ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(detail = when (field) {
            DetailField.Plan -> detail.copy(plannedDate = value)
            DetailField.Due -> detail.copy(dueAt = value)
            DetailField.Reminder -> detail.copy(remindAt = value,
                reminders = detail.reminders.mapIndexed { index, reminder ->
                    if (index == 0) reminder.copy(kind = ReminderDraftKind.Specified, value = value) else reminder
                })
        })
    }

    fun onAddReminder(kind: ReminderDraftKind) {
        val detail = _state.value.detail ?: return
        if (!_state.value.busy) _state.value = _state.value.copy(
            detail = detail.copy(reminders = detail.reminders + ReminderDraft(kind = kind)))
    }

    fun onReminderValueChange(index: Int, value: String) {
        val detail = _state.value.detail ?: return
        if (_state.value.busy || index !in detail.reminders.indices) return
        _state.value = _state.value.copy(detail = detail.copy(
            reminders = detail.reminders.mapIndexed { current, reminder ->
                if (current == index) reminder.copy(value = value) else reminder
            }))
    }

    fun onRemoveReminder(index: Int) {
        val detail = _state.value.detail ?: return
        if (_state.value.busy || index !in detail.reminders.indices) return
        _state.value = _state.value.copy(detail = detail.copy(
            reminders = detail.reminders.filterIndexed { current, _ -> current != index }))
    }

    fun onCancelDetail() {
        if (!_state.value.busy) _state.value = _state.value.copy(detail = null, message = null)
    }

    fun onSaveDetail() {
        val detail = _state.value.detail ?: return
        action {
            val result = useCases.updateItem(detail.original.id, detail.edit(zoneId))
            _state.value = _state.value.copy(detail = null,
                message = result.outcome?.let(::outcomeMessage) ?: "事项已更新（提醒安排未改变）")
            reload()
        }
    }

    fun onComplete(id: String) = action {
        useCases.completeItem(id)
        _state.value = _state.value.copy(detail = null, message = "事项已完成，未来提醒已取消")
        reload()
    }

    fun onRestore(id: String) = action {
        val result = useCases.restoreItem(id)
        _state.value = _state.value.copy(detail = null, message = if (result.pastReminderCleared) {
            "旧提醒已过期，已清除；请重新安排提醒"
        } else "事项已恢复。${outcomeMessage(result.outcome)}")
        reload()
    }

    fun onRetryReminders(id: String) = action {
        val result = useCases.retryReminders(id)
        _state.value = _state.value.copy(message = outcomeMessage(result.outcome))
        reload()
    }

    private fun outcomeMessage(outcome: ReminderOutcome): String = when (outcome) {
        ReminderOutcome.NotRequested -> "事项已保存（未设置提醒）"
        ReminderOutcome.Scheduled -> "已保存并安排提醒"
        ReminderOutcome.NotReady -> "事项已保存，但提醒未就绪（缺精确闹钟权限或系统限制）"
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
