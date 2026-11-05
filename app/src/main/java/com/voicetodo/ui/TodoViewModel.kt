package com.voicetodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.ai.AiAssistClient
import com.voicetodo.ai.AiAssistException
import com.voicetodo.ai.AiDraft
import com.voicetodo.ai.AiPromptContext
import com.voicetodo.ai.AiReminderDraft
import com.voicetodo.ai.AiReminderKind
import com.voicetodo.ai.AiSettings
import com.voicetodo.ai.AiSettingsStore
import com.voicetodo.ai.EncryptedAiSettingsStore
import com.voicetodo.ai.OpenAiCompatibleClient
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoList
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.domain.TodoView
import com.voicetodo.domain.TodoEdit
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.ReminderEdit
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.Clock
import com.voicetodo.domain.SystemClock
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.StorageState
import com.voicetodo.speech.AudioRecorder
import com.voicetodo.speech.OfflineTranscriber
import com.voicetodo.speech.PcmRecorder
import com.voicetodo.speech.SherpaOfflineTranscriber
import com.voicetodo.speech.TranscriptResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
import java.time.DayOfWeek
import java.util.UUID

/** 录音状态机。 */
enum class RecordState { Idle, Recording, Transcribing }

enum class DetailField { Plan, Due, Reminder }

enum class ReminderDraftKind { Specified, Relative }

enum class AiSettingsField { BaseUrl, Model, ApiKey }

data class AiSettingsForm(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val error: String? = null,
) {
    override fun toString(): String =
        "AiSettingsForm(baseUrl=$baseUrl, model=$model, apiKey=***, error=$error)"
}

enum class RepeatDraft { None, Daily, Weekly, Monthly }

data class ReminderDraft(
    val id: String = UUID.randomUUID().toString(),
    val kind: ReminderDraftKind,
    val value: String = "",
)

data class DetailDraft(
    val original: TodoItem?,
    val title: String,
    val plannedDate: String,
    val dueAt: String,
    val remindAt: String,
    val reminders: List<ReminderDraft>,
    val listId: String? = null,
    val repeat: RepeatDraft = RepeatDraft.None,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDay: String = "",
    val repeatTime: String = "09:00",
    val seriesRule: RecurrenceRule? = null,
) {
    fun edit(zone: ZoneId): TodoEdit {
        fun parseTime(text: String, originalTime: Instant?): Instant? {
            if (text == timeText(originalTime, zone)) return originalTime
            return text.trim().takeIf { it.isNotEmpty() }
                ?.let { LocalDateTime.parse(it.replace(' ', 'T')).atZone(zone).toInstant() }
        }
        try {
            val due = parseTime(dueAt, original?.dueAt)
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
            return TodoEdit(
                title = title,
                plannedDate = plannedDate.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                dueAt = due,
                remindAt = reminderEdits.firstOrNull()?.at,
                reminders = reminderEdits,
                listId = listId,
                updateList = true,
            )
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("日期格式：yyyy-MM-dd；时间格式：yyyy-MM-dd HH:mm", e)
        }
    }

    companion object {
        fun from(item: TodoItem, zone: ZoneId, series: TodoItem? = null): DetailDraft {
            val rules = if (item.reminders.isNotEmpty()) item.reminders.map {
                if (it.beforeDue == null) ReminderDraft(it.id, ReminderDraftKind.Specified, timeText(it.at, zone))
                else ReminderDraft(it.id, ReminderDraftKind.Relative, it.beforeDue.toMinutes().toString())
            } else listOf(ReminderDraft(item.id, ReminderDraftKind.Specified, timeText(item.remindAt, zone)))
            return DetailDraft(item, item.title, item.plannedDate?.toString().orEmpty(),
                timeText(item.dueAt, zone), timeText(item.remindAt, zone), rules, item.listId,
                seriesRule = series?.recurrence)
        }
        fun forCreation(title: String, listId: String? = null) = DetailDraft(
            null, title, "", "", "", listOf(ReminderDraft(kind = ReminderDraftKind.Specified)), listId,
        )
        fun timeText(at: Instant?, zone: ZoneId): String =
            at?.atZone(zone)?.toLocalDateTime()?.toString()?.replace('T', ' ').orEmpty()
    }
}

data class UiState(
    val items: List<TodoItem> = emptyList(),
    val completedItems: List<TodoItem> = emptyList(),
    val todoLists: List<TodoList> = emptyList(),
    val selectedView: TodoView = TodoView.Today,
    val unscheduledOnly: Boolean = false,
    val addOpen: Boolean = false,
    val addTargetListId: String? = null,
    val deliveredIds: Set<String> = emptySet(),
    val detail: DetailDraft? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val storageError: String? = null,
    val now: Instant = Instant.EPOCH,
    val recordState: RecordState = RecordState.Idle,
    val speechReady: Boolean = true,
    val draftText: String = "",
    val draftHasTime: Boolean = false,
    val draftTime: Instant? = null,
    val inputError: String? = null,
    val message: String? = null,
    val capabilities: ReminderCapabilities? = null,
    val aiConfigured: Boolean = false,
    val aiSettingsForm: AiSettingsForm? = null,
    val aiDraft: AiDraft? = null,
    val aiBusy: Boolean = false,
    val aiError: String? = null,
    val seriesLabels: Map<String, String> = emptyMap(),
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
    private val recorder: AudioRecorder = PcmRecorder(),
    private val aiSettingsStore: AiSettingsStore = EncryptedAiSettingsStore(app),
    private val aiClient: AiAssistClient = OpenAiCompatibleClient(),
) : AndroidViewModel(app) {
    private val operations = Mutex()
    private var transcriptionJob: Job? = null
    private var speechGeneration = 0L
    private var draftRevision = 0L
    private var recordingStartRevision = 0L
    private var aiJob: Job? = null
    private var aiGeneration = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val speechReady = transcriber.isReady()
        _state.value = _state.value.copy(
            capabilities = capabilities(),
            now = clock.now(),
            speechReady = speechReady,
            inputError = if (!speechReady) "随包离线识别模型缺失或不完整；仍可使用文字输入" else null,
            aiConfigured = runCatching { aiSettingsStore.load() }.getOrNull() != null,
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
        val lists = useCases.listTodoLists()
        val labels = useCases.listRecurrenceSeries().associate {
            it.id to recurrenceLabel(it.recurrence!!)
        }
        val delivered = (pending + completed).filter { useCases.wasDelivered(it) }.map { it.id }.toSet()
        val selected = _state.value.selectedView
        val safeView = if (selected is TodoView.CustomList &&
            lists.none { it.id == selected.listId }) TodoView.Inbox else selected
        _state.value = _state.value.copy(items = pending, completedItems = completed,
            todoLists = lists, selectedView = safeView,
            deliveredIds = delivered, now = clock.now(), loading = false, seriesLabels = labels)
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { operations.withLock { block() } } catch (e: Exception) {
                if (e is CancellationException) throw e
                val error = e.message ?: "操作失败"
                _state.value = _state.value.copy(
                    message = error,
                    aiError = if (_state.value.aiDraft != null) error else _state.value.aiError,
                )
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    fun onRefresh() {
        refreshCapabilities()
        if (!_state.value.busy) refresh()
    }

    fun onSelectView(view: TodoView) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            selectedView = view,
            unscheduledOnly = if (view is TodoView.Inbox || view is TodoView.CustomList) {
                _state.value.unscheduledOnly
            } else false,
            message = null,
        )
    }

    fun onToggleUnscheduledFilter() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(unscheduledOnly = !_state.value.unscheduledOnly)
    }

    /** 打开一个空白添加流程；误触时可直接取消，不产生事项。 */
    fun onOpenAdd(startVoice: Boolean = false) {
        val current = _state.value
        if (current.busy || current.addOpen) return
        val target = (current.selectedView as? TodoView.CustomList)?.listId
        _state.value = current.copy(
            addOpen = true,
            addTargetListId = target,
            draftText = "",
            draftHasTime = false,
            draftTime = null,
            inputError = null,
            message = null,
        )
        if (startVoice) onToggleRecord()
    }

    fun onCloseAdd() {
        if (_state.value.busy) return
        onCancelRecording()
        invalidateAiRequest()
        draftRevision++
        _state.value = _state.value.copy(
            addOpen = false,
            addTargetListId = null,
            draftText = "",
            draftHasTime = false,
            draftTime = null,
            inputError = null,
            message = null,
        )
    }

    fun onCreateList(name: String) = action {
        val list = useCases.createList(name)
        _state.value = _state.value.copy(
            selectedView = TodoView.CustomList(list.id),
            unscheduledOnly = false,
            message = "清单「${list.name}」已创建",
        )
        reload()
    }

    fun onRenameList(id: String, name: String) = action {
        val list = useCases.renameList(id, name)
        _state.value = _state.value.copy(message = "清单已重命名为「${list.name}」")
        reload()
    }

    fun onDeleteList(id: String) = action {
        useCases.deleteList(id)
        if ((_state.value.selectedView as? TodoView.CustomList)?.listId == id) {
            _state.value = _state.value.copy(selectedView = TodoView.Inbox, unscheduledOnly = false)
        }
        _state.value = _state.value.copy(message = "清单已删除，事项已移回收集箱")
        reload()
    }

    fun onRetryStorage() = action { storage.initialize(); reload() }

    fun refreshCapabilities() {
        val ready = transcriber.isReady()
        _state.value = _state.value.copy(
            capabilities = capabilities(),
            speechReady = ready,
            inputError = if (!ready) "随包离线识别模型缺失或不完整；仍可使用文字输入"
                else _state.value.inputError?.takeUnless { it.startsWith("随包离线识别模型") },
        )
    }

    fun onToggleRecord() {
        when (_state.value.recordState) {
            RecordState.Idle -> {
                invalidateAiRequest()
                if (_state.value.capabilities?.canRecordAudio != true) {
                    _state.value = _state.value.copy(
                        inputError = "未授予麦克风权限；仍可使用文字输入",
                    )
                    return
                }
                if (!transcriber.isReady()) {
                    _state.value = _state.value.copy(
                        speechReady = false,
                        inputError = "随包离线识别模型缺失或不完整；仍可使用文字输入",
                    )
                    return
                }
                if (!recorder.start()) {
                    _state.value = _state.value.copy(
                        inputError = "麦克风正被占用或不可用；仍可使用文字输入",
                    )
                } else {
                    recordingStartRevision = draftRevision
                    _state.value = _state.value.copy(
                        recordState = RecordState.Recording,
                        inputError = null,
                        message = null,
                    )
                }
            }
            RecordState.Recording -> stopAndTranscribe()
            RecordState.Transcribing -> Unit
        }
    }

    private fun stopAndTranscribe() {
        val samples = recorder.stop()
        beginTranscription(samples, recorder.sampleRate, recordingStartRevision)
    }

    private fun beginTranscription(samples: FloatArray, sampleRate: Int, sourceRevision: Long) {
        val generation = ++speechGeneration
        _state.value = _state.value.copy(recordState = RecordState.Transcribing, inputError = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (samples.isEmpty()) {
                    _state.value = _state.value.copy(
                        recordState = RecordState.Idle,
                        inputError = "没有录到音频；已有文字已保留",
                    )
                    return@launch
                }
                val result = withContext(Dispatchers.Default) {
                    transcriber.transcribe(samples, sampleRate)
                }
                if (generation != speechGeneration) return@launch
                when (result) {
                    is TranscriptResult.Success -> {
                        val userEditedDuringRecording = draftRevision != sourceRevision
                        if (!userEditedDuringRecording) draftRevision++
                        _state.value = _state.value.copy(
                            recordState = RecordState.Idle,
                            draftText = if (userEditedDuringRecording) _state.value.draftText else result.text,
                            inputError = null,
                            message = if (userEditedDuringRecording) "识别完成；已保留录音期间编辑的文字" else null,
                        )
                    }
                    is TranscriptResult.Failure ->
                        _state.value = _state.value.copy(
                            recordState = RecordState.Idle,
                            inputError = "${result.message}；已有文字已保留",
                        )
                }
            } finally {
                if (generation == speechGeneration) transcriptionJob = null
            }
        }
        transcriptionJob = job
        // 即使协程在真正开始前就被取消，也必须释放本次临时采样。
        job.invokeOnCompletion { samples.fill(0f) }
        job.start()
    }

    fun onDraftTextChange(text: String) {
        invalidateAiRequest()
        draftRevision++
        _state.value = _state.value.copy(draftText = text, inputError = null)
    }

    /** 只取消本次录音/识别，保留已经取得或手工输入的文字。 */
    fun onCancelRecording() {
        when (_state.value.recordState) {
            RecordState.Idle -> return
            RecordState.Recording -> recorder.cancel()
            RecordState.Transcribing -> transcriptionJob?.cancel()
        }
        speechGeneration++
        transcriptionJob = null
        _state.value = _state.value.copy(recordState = RecordState.Idle, inputError = null)
    }

    /** 把当前识别文字带入已有事项详情，由用户补全字段后创建。 */
    fun onOpenManualCreate() {
        val s = _state.value
        if (s.recordState != RecordState.Idle) {
            _state.value = s.copy(inputError = "请先结束或取消当前录音识别")
            return
        }
        if (s.draftText.isBlank()) {
            _state.value = s.copy(inputError = "请先输入或识别事项文字")
            return
        }
        _state.value = s.copy(
            detail = DetailDraft.forCreation(s.draftText, s.addTargetListId),
            inputError = null,
            message = null,
        )
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
        if (s.recordState != RecordState.Idle) {
            _state.value = s.copy(inputError = "请先结束或取消当前录音识别")
            return
        }
        val at = if (withoutReminder) null else s.draftTime
        if (!withoutReminder && (!s.draftHasTime || at == null)) {
            _state.value = s.copy(message = "请先选择提醒时间")
            return
        }
        action {
            val result = useCases.confirmDraft(s.draftText, at, listId = s.addTargetListId)
            _state.value = _state.value.copy(
                addOpen = false, addTargetListId = null,
                draftText = "", draftHasTime = false, draftTime = null,
                inputError = null,
                message = outcomeMessage(result.outcome),
            )
            reload()
        }
    }

    fun onCancel() {
        onCloseAdd()
    }

    fun onOpenAiSettings() {
        val saved = runCatching { aiSettingsStore.load() }.getOrNull()
        _state.value = _state.value.copy(
            aiSettingsForm = AiSettingsForm(
                baseUrl = saved?.baseUrl.orEmpty(),
                model = saved?.model.orEmpty(),
                apiKey = saved?.apiKey.orEmpty(),
            ),
            aiConfigured = saved != null,
            aiError = null,
        )
    }

    fun onAiSettingsChange(field: AiSettingsField, value: String) {
        val form = _state.value.aiSettingsForm ?: return
        _state.value = _state.value.copy(
            aiSettingsForm = when (field) {
                AiSettingsField.BaseUrl -> form.copy(baseUrl = value, error = null)
                AiSettingsField.Model -> form.copy(model = value, error = null)
                AiSettingsField.ApiKey -> form.copy(apiKey = value, error = null)
            }
        )
    }

    fun onSaveAiSettings() {
        val form = _state.value.aiSettingsForm ?: return
        val clean = try {
            AiSettings(form.baseUrl, form.model, form.apiKey).validate()
        } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(aiSettingsForm = form.copy(error = e.message))
            return
        }
        try {
            aiSettingsStore.save(clean)
            _state.value = _state.value.copy(
                aiSettingsForm = null,
                aiConfigured = true,
                aiError = null,
                message = "AI 服务配置已保存（密钥已加密存储，不进入日志或备份）",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                aiSettingsForm = form.copy(error = "配置保存失败，请重试"),
            )
        }
    }

    fun onClearAiSettings() {
        try {
            aiSettingsStore.clear()
            _state.value = _state.value.copy(
                aiSettingsForm = null,
                aiConfigured = false,
                aiError = null,
                message = "已清除 AI 配置；仍可手动创建事项",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                aiSettingsForm = (_state.value.aiSettingsForm ?: AiSettingsForm())
                    .copy(error = "清除配置失败，请重试"),
            )
        }
    }

    fun onCloseAiSettings() {
        _state.value = _state.value.copy(aiSettingsForm = null, aiError = null)
    }

    /** 用户主动提交当前文字；失败不自动重试，也不清空文字。 */
    fun onOrganizeWithAi() {
        val current = _state.value
        if (current.aiBusy) return
        if (current.recordState != RecordState.Idle) {
            _state.value = current.copy(inputError = "请先结束或取消当前录音识别")
            return
        }
        val settings = runCatching { aiSettingsStore.load() }.getOrNull()
        if (settings == null) {
            _state.value = current.copy(
                aiSettingsForm = AiSettingsForm(),
                aiError = null,
                message = "请先在高级设置中配置 AI 服务地址、模型和密钥",
            )
            return
        }
        if (current.draftText.isBlank()) {
            _state.value = current.copy(inputError = "请先输入或识别事项文字")
            return
        }

        aiGeneration++
        aiJob?.cancel()
        val generation = aiGeneration
        val source = current.draftText
        _state.value = current.copy(aiBusy = true, aiError = null, inputError = null, message = null)
        aiJob = viewModelScope.launch {
            try {
                val draft = aiClient.organize(
                    source,
                    AiPromptContext(now = clock.now(), zoneId = zoneId),
                    settings,
                )
                if (generation == aiGeneration) {
                    _state.value = _state.value.copy(aiBusy = false, aiDraft = draft, aiError = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == aiGeneration) {
                    _state.value = _state.value.copy(
                        aiBusy = false,
                        aiError = (e as? AiAssistException)?.message
                            ?: "AI 整理失败；文字已保留，可手动创建",
                    )
                }
            }
        }
    }

    fun onCancelAiRequest() = invalidateAiRequest(showMessage = true)

    private fun invalidateAiRequest(showMessage: Boolean = false) {
        if (aiJob == null && !_state.value.aiBusy) return
        aiGeneration++
        aiJob?.cancel()
        aiJob = null
        _state.value = _state.value.copy(
            aiBusy = false,
            aiError = null,
            message = if (showMessage) "已取消 AI 整理，文字已保留" else _state.value.message,
        )
    }

    fun onAiDraftTitleChange(title: String) = updateAiDraft { it.copy(title = title) }
    fun onAiDraftPlannedDateChange(value: String) = updateAiDraft { it.copy(plannedDate = value) }
    fun onAiDraftDueChange(value: String) = updateAiDraft { it.copy(dueAt = value) }
    fun onAiDraftQuadrantChange(quadrant: Quadrant) = updateAiDraft { it.copy(quadrant = quadrant) }
    fun onAiDraftClarificationAck(checked: Boolean) = updateAiDraft {
        it.copy(noReminderAcknowledged = checked)
    }

    private fun updateAiDraft(block: (AiDraft) -> AiDraft) {
        if (_state.value.busy) return
        val draft = _state.value.aiDraft ?: return
        _state.value = _state.value.copy(aiDraft = block(draft), aiError = null)
    }

    fun onAddAiReminder(kind: AiReminderKind) = updateAiDraft {
        it.copy(reminders = it.reminders + AiReminderDraft(kind = kind))
    }

    fun onAiReminderValueChange(index: Int, value: String) = updateAiDraft { draft ->
        draft.copy(reminders = draft.reminders.mapIndexed { current, reminder ->
            if (current == index) reminder.copy(value = value) else reminder
        })
    }

    fun onRemoveAiReminder(index: Int) = updateAiDraft { draft ->
        draft.copy(reminders = draft.reminders.filterIndexed { current, _ -> current != index })
    }

    fun onCancelAiDraft() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            aiDraft = null,
            aiError = null,
            message = "已取消 AI 草稿，文字已保留，可继续编辑或手动创建",
        )
    }

    fun onConfirmAiDraft() {
        val draft = _state.value.aiDraft ?: return
        if (draft.requiresNoReminderAcknowledgement) {
            _state.value = _state.value.copy(
                aiError = "时间还不明确：请补充提醒时间，或勾选“确认不设提醒”",
            )
            return
        }
        val edit = try {
            draft.toTodoEdit(zoneId)
        } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(aiError = e.message ?: "草稿字段无效，请检查")
            return
        }
        action {
            val result = useCases.confirmDraft(
                title = edit.title,
                remindAt = edit.remindAt,
                plannedDate = edit.plannedDate,
                dueAt = edit.dueAt,
                reminders = edit.reminders.orEmpty(),
                quadrant = draft.quadrant,
                itemId = draft.itemId,
                suggestedQuadrant = draft.suggestedQuadrant,
                listId = _state.value.addTargetListId,
            )
            _state.value = _state.value.copy(
                addOpen = false,
                addTargetListId = null,
                aiDraft = null,
                aiError = null,
                draftText = "",
                draftHasTime = false,
                draftTime = null,
                inputError = null,
                message = outcomeMessage(result.outcome),
            )
            reload()
        }
    }

    fun onDelete(id: String) {
        action {
            useCases.deleteItem(id)
            reload()
        }
    }

    /** 四象限移动只改分类；提醒、截止、计划日期与清单均保持不变。 */
    fun onMoveQuadrant(id: String, quadrant: Quadrant) = action {
        useCases.moveToQuadrant(id, quadrant)
        _state.value = _state.value.copy(message = "已移到「${quadrant.label}」")
        reload()
    }

    fun onOpenDetail(id: String) = action {
        val item = useCases.findItem(id)
        val series = item.seriesId?.let { runCatching { useCases.findItem(it) }.getOrNull() }
        _state.value = _state.value.copy(detail = DetailDraft.from(item, zoneId, series))
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

    fun onDetailListChange(listId: String?) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(detail = _state.value.detail?.copy(listId = listId))
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

    fun onRepeatChange(repeat: RepeatDraft) {
        val detail = _state.value.detail ?: return
        if (_state.value.busy || detail.original != null) return
        val cleared = if (repeat == RepeatDraft.None) detail else detail.copy(plannedDate = "", dueAt = "")
        _state.value = _state.value.copy(detail = cleared.copy(repeat = repeat))
    }

    fun onWeekdayToggle(day: DayOfWeek) {
        val detail = _state.value.detail ?: return
        if (_state.value.busy || detail.original != null) return
        val weekdays = if (day in detail.weekdays) detail.weekdays - day else detail.weekdays + day
        _state.value = _state.value.copy(detail = detail.copy(weekdays = weekdays))
    }

    fun onWorkdaysSelected() {
        val detail = _state.value.detail ?: return
        if (_state.value.busy || detail.original != null) return
        val workdays = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        _state.value = _state.value.copy(detail = detail.copy(weekdays = workdays))
    }

    fun onMonthDayChange(value: String) {
        val detail = _state.value.detail ?: return
        if (_state.value.busy || detail.original != null) return
        _state.value = _state.value.copy(
            detail = detail.copy(monthDay = value.filter(Char::isDigit).take(2)))
    }

    fun onRepeatTimeChange(value: String) {
        val detail = _state.value.detail ?: return
        if (_state.value.busy || detail.original != null) return
        _state.value = _state.value.copy(detail = detail.copy(repeatTime = value))
    }

    fun onCancelDetail() {
        if (!_state.value.busy) _state.value = _state.value.copy(detail = null, message = null)
    }

    fun onSaveDetail() {
        val detail = _state.value.detail ?: return
        action {
            val edit = detail.edit(zoneId)
            val original = detail.original
            val message = if (original == null) {
                if (detail.repeat != RepeatDraft.None) {
                    val result = useCases.createRecurring(edit.title, recurrenceRule(detail), edit.listId)
                    _state.value = _state.value.copy(addOpen = false, addTargetListId = null)
                    recurrenceMessage(result.outcome)
                } else {
                    require(edit.reminders.isNullOrEmpty() || edit.reminders.size == 1) {
                        "手动创建暂只支持一个指定提醒"
                    }
                    require(edit.reminders.orEmpty().all { it.beforeDue == null }) {
                        "新事项请先创建，再在详情中添加相对提醒"
                    }
                    val result = useCases.confirmDraft(
                        edit.title,
                        edit.remindAt,
                        edit.plannedDate,
                        edit.dueAt,
                        listId = edit.listId,
                    )
                    draftRevision++
                    _state.value = _state.value.copy(
                        addOpen = false,
                        addTargetListId = null,
                        draftText = "",
                        draftHasTime = false,
                        draftTime = null,
                        inputError = null,
                    )
                    outcomeMessage(result.outcome)
                }
            } else {
                val result = useCases.updateItem(original.id, edit)
                result.outcome?.let(::outcomeMessage) ?: "事项已更新（提醒安排未改变）"
            }
            _state.value = _state.value.copy(detail = null, message = message)
            reload()
        }
    }

    fun onComplete(id: String) = action {
        val recurringInstance = useCases.findItem(id).seriesId != null
        useCases.completeItem(id)
        _state.value = _state.value.copy(detail = null, message = if (recurringInstance) {
            "本次已完成，后续重复实例仍会按时提醒"
        } else "事项已完成，未来提醒已取消")
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

    fun onStopRecurrence(id: String) = action {
        useCases.stopRecurrence(id)
        _state.value = _state.value.copy(detail = null, message = "已结束整个重复安排，已完成历史仍保留")
        reload()
    }

    /** 详情页选择通知呈现方式：普通提示音振动（默认）或持续响铃 60 秒。 */
    fun onSetPersistentRing(id: String, enabled: Boolean) = action {
        useCases.setPersistentRing(id, enabled)
        _state.value = _state.value.copy(
            message = if (enabled) "已改为持续响铃（默认 60 秒后停止并保留通知）" else "已改为普通提示音与振动",
        )
        reload()
    }


    private fun recurrenceRule(detail: DetailDraft): RecurrenceRule {
        val frequency = when (detail.repeat) {
            RepeatDraft.Daily -> RecurrenceFrequency.Daily
            RepeatDraft.Weekly -> RecurrenceFrequency.Weekly
            RepeatDraft.Monthly -> RecurrenceFrequency.Monthly
            RepeatDraft.None -> throw IllegalArgumentException("请选择重复频率")
        }
        val time = try {
            LocalTime.parse(detail.repeatTime.trim())
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("重复提醒时间格式：HH:mm", e)
        }
        val monthDay = if (frequency == RecurrenceFrequency.Monthly) {
            detail.monthDay.toIntOrNull()
                ?: throw IllegalArgumentException("每月重复请输入 1 至 31 的日期")
        } else null
        val rule = RecurrenceRule(
            frequency = frequency,
            startDate = clock.now().atZone(zoneId).toLocalDate(),
            weekdays = detail.weekdays,
            dayOfMonth = monthDay,
            reminderTimes = listOf(time),
        )
        rule.validate()
        return rule
    }

    private fun recurrenceMessage(outcome: ReminderOutcome): String = when (outcome) {
        ReminderOutcome.NotRequested -> "重复事项已保存"
        ReminderOutcome.Scheduled -> "重复事项已保存，接下来两次实例已安排提醒"
        ReminderOutcome.NotReady -> "重复事项已保存，但部分实例提醒未就绪（缺精确闹钟权限或系统限制）"
    }

    private fun outcomeMessage(outcome: ReminderOutcome): String = when (outcome) {
        ReminderOutcome.NotRequested -> "事项已保存（未设置提醒）"
        ReminderOutcome.Scheduled -> "已保存并安排提醒"
        ReminderOutcome.NotReady -> "事项已保存，但提醒未就绪（缺精确闹钟权限或系统限制）"
    }

    /** 供仪器测试注入固定音频并识别（跳过麦克风）。 */
    fun transcribeSamplesForTest(samples: FloatArray, sampleRate: Int) {
        beginTranscription(samples, sampleRate, draftRevision)
    }

    override fun onCleared() {
        transcriptionJob?.cancel()
        speechGeneration++
        aiGeneration++
        aiJob?.cancel()
        recorder.release()
        super.onCleared()
    }
}
