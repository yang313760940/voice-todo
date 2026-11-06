package com.voicetodo.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicetodo.VoiceTodoApp
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.ai.AiAssistClient
import com.voicetodo.ai.AiAssistException
import com.voicetodo.ai.AiDraft
import com.voicetodo.ai.AiRecurrenceDraft
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
import com.voicetodo.domain.TodoDraftInput
import com.voicetodo.domain.TodoView
import com.voicetodo.domain.TodoEdit
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.ReminderEdit
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.Clock
import com.voicetodo.domain.SystemClock
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.RecurringDeleteScope
import com.voicetodo.domain.RecurringEditScope
import com.voicetodo.domain.MissedReminder
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.StorageState
import com.voicetodo.speech.DefaultSpeechRecorderFactory
import com.voicetodo.speech.DoubaoSpeechSettings
import com.voicetodo.speech.EncryptedSpeechSettingsStore
import com.voicetodo.speech.OkHttpSpeechStreamFactory
import com.voicetodo.speech.SpeechEvent
import com.voicetodo.speech.SpeechRecorder
import com.voicetodo.speech.SpeechRecorderFactory
import com.voicetodo.speech.SpeechSettingsStore
import com.voicetodo.speech.SpeechStream
import com.voicetodo.speech.SpeechStreamFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import java.time.DayOfWeek
import java.util.UUID

enum class DetailField { Plan, Due, Reminder }

enum class ReminderDraftKind { Specified, Relative }

enum class AiSettingsField { BaseUrl, Model, ApiKey }

enum class SpeechSettingsField { ApiKey, ResourceId }

enum class SpeechUiState { Idle, Connecting, Recording, Finishing, Failed }

data class AiSettingsForm(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val speechApiKey: String = "",
    val speechResourceId: String = DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
    val speechError: String? = null,
    val error: String? = null,
) {
    override fun toString(): String =
        "AiSettingsForm(baseUrl=$baseUrl, model=$model, apiKey=***, " +
            "speechApiKey=***, speechResourceId=$speechResourceId, " +
            "speechError=$speechError, error=$error)"
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
    val note: String = "",
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
                note = note,
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
            return DetailDraft(
                original = item,
                title = item.title,
                note = item.note,
                plannedDate = item.plannedDate?.toString().orEmpty(),
                dueAt = timeText(item.dueAt, zone),
                remindAt = timeText(item.remindAt, zone),
                reminders = rules,
                listId = item.listId,
                seriesRule = series?.recurrence,
            )
        }
        fun forCreation(title: String, listId: String? = null, note: String = "") =
            DetailDraft(
                original = null,
                title = title,
                note = note,
                plannedDate = "",
                dueAt = "",
                remindAt = "",
                reminders = listOf(ReminderDraft(kind = ReminderDraftKind.Specified)),
                listId = listId,
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
    val draftText: String = "",
    val draftNote: String = "",
    val draftHasTime: Boolean = false,
    val draftTime: Instant? = null,
    val inputError: String? = null,
    val message: String? = null,
    val capabilities: ReminderCapabilities? = null,
    val aiConfigured: Boolean = false,
    val aiSettingsForm: AiSettingsForm? = null,
    val aiDrafts: List<AiDraft> = emptyList(),
    val selectedAiDraftId: String? = null,
    val aiBusy: Boolean = false,
    val aiError: String? = null,
    val speechConfigured: Boolean = false,
    val speechState: SpeechUiState = SpeechUiState.Idle,
    val speechPartialText: String = "",
    val speechConsentVisible: Boolean = false,
    val voicePanelOpen: Boolean = false,
    val voiceElapsedMs: Long = 0,
    val voiceWaveform: List<Float> = emptyList(),
    val voiceHasSpoken: Boolean = false,
    val voiceTextEditing: Boolean = false,
    val voiceLimitReached: Boolean = false,
    val voiceDiscardConfirmVisible: Boolean = false,
    /** 自动保存倒计时截止点；null 表示当前没有待执行的自动保存。 */
    val autoSaveDeadline: Instant? = null,
    val autoSaveRemainingMs: Long = 0,
    /** 最近一次批量保存的撤销窗口；null 表示不可撤销。 */
    val pendingUndo: PendingBatchUndo? = null,
    val seriesLabels: Map<String, String> = emptyMap(),
    val missedReminders: List<MissedReminder> = emptyList(),
    /** 删除重复实例前等待用户选择作用范围。 */
    val pendingRecurringDeleteId: String? = null,
    /** 已展开的积压系列 id；仅影响列表折叠展示。 */
    val expandedBacklogSeries: Set<String> = emptySet(),
) {
    val aiDraft: AiDraft?
        get() = aiDrafts.firstOrNull { it.itemId == selectedAiDraftId } ?: aiDrafts.firstOrNull()
}

/** 刚保存的一批事项及其原始草稿，供五秒内整批撤回。 */
data class PendingBatchUndo(
    val itemIds: List<String>,
    val drafts: List<AiDraft>,
    val deadline: Instant,
) {
    val count: Int get() = drafts.size
}

/**
 * 单页待办 ViewModel：可编辑标题 → 手选时间 → 保存/取消。
 * 语音入口在接入在线识别前只做提示；通过 [VoiceTodoApp.useCases] 统一入口持久化并调度系统闹钟。
 */
class TodoViewModel @JvmOverloads constructor(
    app: Application,
    private val useCases: TodoUseCases = (app as VoiceTodoApp).useCases,
    private val storage: RoomTodoRepository = (app as VoiceTodoApp).repository,
    private val capabilities: () -> ReminderCapabilities = { (app as VoiceTodoApp).scheduler.capabilities() },
    private val clock: Clock = SystemClock(),
    zoneId: ZoneId? = null,
    private val aiSettingsStore: AiSettingsStore = EncryptedAiSettingsStore(app),
    private val aiClient: AiAssistClient = OpenAiCompatibleClient(),
    private val reconcileReminders: suspend () -> Unit = {
        if (app is VoiceTodoApp) app.reminderRecovery.reconcile()
    },
    private val speechSettingsStore: SpeechSettingsStore = EncryptedSpeechSettingsStore(app),
    private val speechStreamFactory: SpeechStreamFactory = OkHttpSpeechStreamFactory(),
    private val speechRecorderFactory: SpeechRecorderFactory = DefaultSpeechRecorderFactory,
    private val hasRecordAudioPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    },
) : AndroidViewModel(app) {
    private val configuredZoneId = zoneId
    val zoneId: ZoneId get() = configuredZoneId ?: ZoneId.systemDefault()
    private val operations = Mutex()
    private var aiJob: Job? = null
    private var aiGeneration = 0L
    private var speechJob: Job? = null
    private var speechStream: SpeechStream? = null
    private var speechRecorder: SpeechRecorder? = null
    private var speechGeneration = 0L
    private var voiceTimerJob: Job? = null
    private var voiceStartedAt: Instant? = null
    @Volatile private var voiceLastSpeechAt: Instant? = null
    private var autoSaveJob: Job? = null
    private var undoJob: Job? = null
    private var autoSaveSuppressed = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(
            capabilities = capabilities(),
            now = clock.now(),
            aiConfigured = runCatching { aiSettingsStore.load() }.getOrNull() != null,
            speechConfigured = runCatching { speechSettingsStore.load() }.getOrNull() != null,
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

    private fun refresh(reconcile: Boolean = false) {
        viewModelScope.launch {
            try {
                operations.withLock {
                    if (reconcile) reconcileReminders()
                    reload()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(message = e.message)
            }
        }
    }

    /** 备份恢复替换了整库数据后，丢弃旧编辑上下文并重新读取所有页面状态。 */
    fun refreshAfterExternalDataChange() {
        cancelAutoSave()
        undoJob?.cancel()
        undoJob = null
        _state.value = _state.value.copy(
            detail = null,
            addOpen = false,
            aiDrafts = emptyList(),
            selectedAiDraftId = null,
            aiError = null,
            aiBusy = false,
            speechState = SpeechUiState.Idle,
            speechPartialText = "",
            speechConsentVisible = false,
            voicePanelOpen = false,
            voiceElapsedMs = 0,
            voiceWaveform = emptyList(),
            voiceHasSpoken = false,
            voiceTextEditing = false,
            voiceLimitReached = false,
            voiceDiscardConfirmVisible = false,
            autoSaveDeadline = null,
            autoSaveRemainingMs = 0,
            pendingUndo = null,
            pendingRecurringDeleteId = null,
            message = null,
        )
        cancelSpeechResources()
        refresh()
    }

    private suspend fun reload() {
        val pending = useCases.listIncomplete()
        val completed = useCases.listCompleted()
        val lists = useCases.listTodoLists()
        val missed = useCases.listMissedReminders()
        val labels = useCases.listRecurrenceSeries().associate {
            it.id to recurrenceLabel(it.recurrence!!)
        }
        val delivered = (pending + completed).filter { useCases.wasDelivered(it) }.map { it.id }.toSet()
        val selected = _state.value.selectedView
        val safeView = if (selected is TodoView.CustomList &&
            lists.none { it.id == selected.listId }) TodoView.Inbox else selected
        _state.value = _state.value.copy(items = pending, completedItems = completed,
            todoLists = lists, selectedView = safeView,
            deliveredIds = delivered, now = clock.now(), loading = false, seriesLabels = labels,
            missedReminders = missed)
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
        if (!_state.value.busy) refresh(reconcile = true)
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
        val speech = runCatching { speechSettingsStore.load() }.getOrNull()
        val target = (current.selectedView as? TodoView.CustomList)?.listId
        _state.value = current.copy(
            addOpen = true,
            addTargetListId = target,
            draftText = "",
            draftNote = "",
            draftHasTime = false,
            draftTime = null,
            inputError = null,
            message = null,
            speechConfigured = speech != null,
            speechState = SpeechUiState.Idle,
            speechPartialText = "",
            speechConsentVisible = false,
            voicePanelOpen = startVoice,
            voiceElapsedMs = 0,
            voiceWaveform = emptyList(),
            voiceHasSpoken = false,
            voiceTextEditing = false,
            voiceLimitReached = false,
            voiceDiscardConfirmVisible = false,
            autoSaveDeadline = null,
            autoSaveRemainingMs = 0,
        )
        autoSaveSuppressed = false
        if (startVoice) onStartSpeech()
    }

    fun onCloseAdd() {
        if (_state.value.busy) return
        invalidateAiRequest()
        cancelSpeechResources()
        cancelAutoSave()
        _state.value = _state.value.copy(
            addOpen = false,
            addTargetListId = null,
            draftText = "",
            draftNote = "",
            draftHasTime = false,
            draftTime = null,
            inputError = null,
            message = null,
            speechState = SpeechUiState.Idle,
            speechPartialText = "",
            speechConsentVisible = false,
            voicePanelOpen = false,
            voiceElapsedMs = 0,
            voiceWaveform = emptyList(),
            voiceHasSpoken = false,
            voiceTextEditing = false,
            voiceLimitReached = false,
            voiceDiscardConfirmVisible = false,
            autoSaveDeadline = null,
            autoSaveRemainingMs = 0,
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
        _state.value = _state.value.copy(capabilities = capabilities())
    }

    fun onDraftTextChange(text: String) {
        invalidateAiRequest()
        cancelAutoSave()
        _state.value = _state.value.copy(
            draftText = text,
            inputError = null,
            voiceTextEditing = _state.value.voiceTextEditing || _state.value.voicePanelOpen,
        )
    }

    /** 点击语音入口或录音面板的“开始录音”。首次使用会先请求上传告知。 */
    fun onStartSpeech() {
        val current = _state.value
        if (!current.addOpen) return
        if (current.speechState == SpeechUiState.Connecting ||
            current.speechState == SpeechUiState.Recording ||
            current.speechState == SpeechUiState.Finishing
        ) return
        val retryingAfterFailure = current.speechState == SpeechUiState.Failed
        if (retryingAfterFailure) autoSaveSuppressed = true
        val settings = runCatching { speechSettingsStore.load() }.getOrNull()
        if (settings == null) {
            _state.value = current.copy(
                speechConfigured = false,
                inputError = "语音识别未配置；请先在高级设置中填写豆包语音 API Key",
            )
            return
        }
        _state.value = current.copy(
            speechConfigured = true,
            inputError = null,
            message = null,
            voiceDiscardConfirmVisible = false,
        )
        if (!settings.uploadAcknowledged) {
            _state.value = _state.value.copy(speechConsentVisible = true)
            return
        }
        startSpeechCapture(settings, suppressAutoSave = retryingAfterFailure)
    }

    fun onConfirmSpeechConsent() {
        val settings = runCatching { speechSettingsStore.load() }.getOrNull()
        if (settings == null) {
            _state.value = _state.value.copy(
                speechConsentVisible = false,
                speechConfigured = false,
                inputError = "语音识别未配置；请先在高级设置中填写豆包语音 API Key",
            )
            return
        }
        val acknowledged = settings.copy(uploadAcknowledged = true)
        try {
            speechSettingsStore.save(acknowledged)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                speechConsentVisible = false,
                inputError = "无法保存录音告知确认，请重试",
            )
            return
        }
        _state.value = _state.value.copy(
            speechConsentVisible = false,
            speechConfigured = true,
        )
        startSpeechCapture(acknowledged, suppressAutoSave = autoSaveSuppressed)
    }

    fun onCancelSpeechConsent() {
        _state.value = _state.value.copy(speechConsentVisible = false)
    }

    private fun startSpeechCapture(
        settings: DoubaoSpeechSettings,
        suppressAutoSave: Boolean = false,
    ) {
        if (!hasRecordAudioPermission()) {
            _state.value = _state.value.copy(
                speechState = SpeechUiState.Failed,
                inputError = "需要麦克风权限才能语音添加；请在系统设置中允许录音",
            )
            return
        }
        val stream = speechStreamFactory.open(settings)
        val recorder = speechRecorderFactory.create()
        cancelSpeechResources()
        cancelAutoSave()
        autoSaveSuppressed = suppressAutoSave
        speechGeneration++
        val generation = speechGeneration
        speechStream = stream
        speechRecorder = recorder
        _state.value = _state.value.copy(
            draftText = "",
            speechPartialText = "",
            aiDrafts = emptyList(),
            selectedAiDraftId = null,
            aiError = null,
            speechState = SpeechUiState.Connecting,
            inputError = null,
            message = null,
            speechConsentVisible = false,
            voiceElapsedMs = 0,
            voiceWaveform = emptyList(),
            voiceHasSpoken = false,
            voiceTextEditing = false,
            voiceLimitReached = false,
            voiceDiscardConfirmVisible = false,
            autoSaveDeadline = null,
            autoSaveRemainingMs = 0,
        )
        voiceStartedAt = clock.now()
        voiceLastSpeechAt = null
        speechJob = viewModelScope.launch {
            stream.events.collect { event ->
                if (generation == speechGeneration) onSpeechEvent(event)
            }
        }
        stream.start("zh-CN")
        val started = recorder.start { chunk ->
            if (generation == speechGeneration) {
                stream.pushPcm(chunk)
                onSpeechAudio(chunk, generation)
            }
        }
        if (!started) {
            stream.cancel()
            speechJob?.cancel()
            speechJob = null
            speechStream = null
            speechRecorder = null
            _state.value = _state.value.copy(
                speechState = SpeechUiState.Failed,
                inputError = "无法启动麦克风；请检查录音权限或其他应用是否占用麦克风",
            )
        } else {
            startVoiceTimer(generation)
        }
    }

    private fun onSpeechAudio(pcm: ByteArray, generation: Long) {
        val level = pcmLevel(pcm)
        val spoke = level >= SPEECH_LEVEL
        val now = clock.now()
        if (spoke) voiceLastSpeechAt = now
        _state.update { current ->
            if (generation != speechGeneration || !current.voicePanelOpen ||
                current.speechState == SpeechUiState.Idle
            ) {
                current
            } else {
                current.copy(
                    voiceElapsedMs = elapsedMillis(now),
                    voiceHasSpoken = current.voiceHasSpoken || spoke,
                    voiceWaveform = (current.voiceWaveform + level.coerceIn(0f, 1f))
                        .takeLast(WAVEFORM_BARS),
                )
            }
        }
    }

    private fun startVoiceTimer(generation: Long) {
        voiceTimerJob?.cancel()
        voiceTimerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(VOICE_TICK_MS)
                if (generation != speechGeneration) break
                onVoiceTick()
                val state = _state.value
                if (state.speechState != SpeechUiState.Recording &&
                    state.speechState != SpeechUiState.Connecting
                ) break
            }
        }
    }

    /** 统一的录音计时入口；测试推进时钟后可直接调用。 */
    internal fun onVoiceTick() {
        val current = _state.value
        if (current.speechState != SpeechUiState.Recording &&
            current.speechState != SpeechUiState.Connecting
        ) return
        val now = clock.now()
        val elapsed = elapsedMillis(now)
        _state.update {
            if (it.voicePanelOpen) it.copy(voiceElapsedMs = elapsed) else it
        }
        when {
            elapsed >= MAX_RECORDING_MS -> stopVoiceCapture(
                "已达到 60 秒录音上限，内容可能尚未说完；请核对文字后手动保存",
                limitReached = true,
            )
            !current.voiceHasSpoken && elapsed >= NO_SPEECH_TIMEOUT_MS -> stopEmptyVoiceCapture()
            current.voiceHasSpoken -> {
                val lastSpeech = voiceLastSpeechAt ?: return
                if (now.toEpochMilli() - lastSpeech.toEpochMilli() >= SILENCE_AFTER_SPEECH_MS) {
                    stopVoiceCapture("连续 2 秒未检测到说话，已自动结束录音")
                }
            }
        }
    }

    private fun stopVoiceCapture(reason: String, limitReached: Boolean = false) {
        voiceTimerJob?.cancel()
        voiceTimerJob = null
        if (_state.value.speechState != SpeechUiState.Recording &&
            _state.value.speechState != SpeechUiState.Connecting
        ) return
        onStopSpeech()
        _state.update {
            it.copy(
                message = reason,
                voiceLimitReached = it.voiceLimitReached || limitReached,
            )
        }
    }

    /** 未开口时直接取消会话，避免把空音频送去转写或整理。 */
    private fun stopEmptyVoiceCapture() {
        voiceTimerJob?.cancel()
        voiceTimerJob = null
        speechRecorder?.stop()
        speechRecorder = null
        speechStream?.cancel()
        speechStream = null
        speechJob?.cancel()
        speechJob = null
        voiceStartedAt = null
        voiceLastSpeechAt = null
        _state.update {
            it.copy(
                speechState = SpeechUiState.Idle,
                speechPartialText = "",
                inputError = null,
                message = "10 秒内未检测到说话，录音已停止；可点小笔直接输入",
            )
        }
    }

    private fun elapsedMillis(now: Instant): Long {
        val started = voiceStartedAt ?: return 0
        return (now.toEpochMilli() - started.toEpochMilli()).coerceAtLeast(0)
    }

    private fun onSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Connected -> {
                if (_state.value.speechState == SpeechUiState.Connecting) {
                    _state.value = _state.value.copy(speechState = SpeechUiState.Recording)
                }
            }
            is SpeechEvent.Partial -> {
                voiceLastSpeechAt = clock.now()
                _state.update {
                    it.copy(
                    draftText = if (it.voiceTextEditing) it.draftText else event.text,
                    speechPartialText = event.text,
                    speechState = if (it.speechState == SpeechUiState.Connecting) {
                        SpeechUiState.Recording
                    } else {
                        it.speechState
                    },
                    voiceHasSpoken = true,
                    )
                }
            }
            is SpeechEvent.Final -> {
                voiceTimerJob?.cancel()
                voiceTimerJob = null
                voiceStartedAt = null
                voiceLastSpeechAt = null
                speechRecorder?.release()
                speechRecorder = null
                speechStream = null
                val current = _state.value
                val edited = current.voiceTextEditing
                val finalText = if (edited) current.draftText else event.text.ifBlank { current.draftText }
                _state.value = current.copy(
                    draftText = finalText,
                    speechPartialText = "",
                    speechState = SpeechUiState.Idle,
                    inputError = null,
                    message = when {
                        edited -> "录音已停止；可继续编辑文字后手动保存"
                        finalText.isBlank() -> current.message ?: "未识别到文字；可点小笔直接输入"
                        else -> "识别完成；请核对文字后手动保存"
                    },
                )
                if (finalText.isNotBlank() && !edited) autoOrganizeAfterSpeech(finalText)
            }
            is SpeechEvent.Failure -> {
                voiceTimerJob?.cancel()
                voiceTimerJob = null
                voiceStartedAt = null
                voiceLastSpeechAt = null
                speechRecorder?.release()
                speechRecorder = null
                speechStream = null
                val existing = _state.value.draftText.ifBlank { _state.value.speechPartialText }
                val log = event.logId?.takeIf { it.isNotBlank() }
                    ?.let { "（logid: $it）" }
                    .orEmpty()
                _state.value = _state.value.copy(
                    draftText = existing,
                    speechPartialText = existing,
                    speechState = SpeechUiState.Failed,
                    inputError = event.message + log,
                )
            }
        }
    }

    /** 手动结束录音；之后只等待最终文字，不自动保存。 */
    fun onStopSpeech() {
        val current = _state.value
        if (current.speechState != SpeechUiState.Recording &&
            current.speechState != SpeechUiState.Connecting
        ) return
        voiceTimerJob?.cancel()
        voiceTimerJob = null
        speechRecorder?.stop()
        speechRecorder = null
        _state.value = current.copy(
            speechState = SpeechUiState.Finishing,
            inputError = null,
            speechPartialText = current.draftText,
        )
        val stream = speechStream
        if (stream == null) {
            _state.value = _state.value.copy(
                speechState = SpeechUiState.Failed,
                inputError = "语音识别连接已丢失，已识别的文字可能不完整",
            )
        } else {
            stream.finish()
        }
    }

    fun onCancelSpeech() {
        val hadText = _state.value.draftText.isNotBlank()
        cancelSpeechResources()
        cancelAutoSave()
        _state.value = _state.value.copy(
            speechState = SpeechUiState.Idle,
            speechPartialText = "",
            speechConsentVisible = false,
            message = if (hadText) "已取消录音，文字已保留" else null,
        )
    }

    /** 小笔入口：立即停止当前录音，保留已有文字并进入可编辑状态。 */
    fun onEditVoiceText() {
        cancelAutoSave()
        if (_state.value.speechState == SpeechUiState.Recording ||
            _state.value.speechState == SpeechUiState.Connecting
        ) {
            onStopSpeech()
        }
        _state.update {
            it.copy(voiceTextEditing = true, voiceDiscardConfirmVisible = false)
        }
    }

    fun onFinishVoiceTextEdit() {
        _state.update { it.copy(voiceTextEditing = false) }
    }

    fun hasUnsavedVoiceContent(): Boolean {
        val s = _state.value
        return s.voicePanelOpen && (
            s.draftText.isNotBlank() || s.aiDrafts.isNotEmpty() || s.aiBusy ||
                s.speechState != SpeechUiState.Idle
            )
    }

    fun onRequestCloseVoicePanel() {
        val current = _state.value
        if (!current.voicePanelOpen) return
        cancelAutoSave()
        if (current.draftText.isBlank() && current.aiDrafts.isEmpty() && !current.aiBusy) {
            onDiscardVoiceDraft()
            return
        }
        if (_state.value.speechState == SpeechUiState.Recording ||
            _state.value.speechState == SpeechUiState.Connecting
        ) {
            onStopSpeech()
        }
        _state.update { it.copy(voiceDiscardConfirmVisible = true) }
    }

    fun onContinueVoiceEditing() {
        cancelSpeechResources()
        cancelAutoSave()
        _state.update {
            it.copy(
                speechState = SpeechUiState.Idle,
                speechPartialText = "",
                voiceDiscardConfirmVisible = false,
                message = "已保留本次内容，请手动保存",
            )
        }
    }

    fun onDiscardVoiceDraft() {
        invalidateAiRequest()
        cancelSpeechResources()
        cancelAutoSave()
        _state.update {
            it.copy(
                addOpen = false,
                addTargetListId = null,
                draftText = "",
                draftNote = "",
                draftHasTime = false,
                draftTime = null,
                aiDrafts = emptyList(),
                selectedAiDraftId = null,
                aiError = null,
                inputError = null,
                message = null,
                speechState = SpeechUiState.Idle,
                speechPartialText = "",
                speechConsentVisible = false,
                voicePanelOpen = false,
                voiceElapsedMs = 0,
                voiceWaveform = emptyList(),
                voiceHasSpoken = false,
                voiceTextEditing = false,
                voiceLimitReached = false,
                voiceDiscardConfirmVisible = false,
                autoSaveDeadline = null,
                autoSaveRemainingMs = 0,
            )
        }
    }

    /** 403 时由用户手动切换资源；不自动重试，也不把失败当成功。 */
    fun onSwitchToLegacySpeechResource() {
        val settings = runCatching { speechSettingsStore.load() }.getOrNull()
        if (settings == null) {
            _state.value = _state.value.copy(
                inputError = "语音识别未配置；请先在高级设置中填写豆包语音 API Key",
            )
            return
        }
        try {
            speechSettingsStore.save(
                settings.copy(resourceId = DoubaoSpeechSettings.LEGACY_RESOURCE_ID),
            )
            _state.value = _state.value.copy(
                speechConfigured = true,
                speechState = SpeechUiState.Idle,
                inputError = null,
                message = "已切换到 1.0 小时版；请重新录音",
                aiSettingsForm = _state.value.aiSettingsForm?.copy(
                    speechResourceId = DoubaoSpeechSettings.LEGACY_RESOURCE_ID,
                    speechError = null,
                ),
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                inputError = "切换资源失败，请到高级设置手动修改",
            )
        }
    }

    private fun cancelSpeechResources() {
        voiceTimerJob?.cancel()
        voiceTimerJob = null
        voiceStartedAt = null
        voiceLastSpeechAt = null
        speechGeneration++
        speechJob?.cancel()
        speechJob = null
        speechStream?.cancel()
        speechStream = null
        speechRecorder?.cancel()
        speechRecorder?.release()
        speechRecorder = null
    }

    /** 长按麦克风直接打开完整手动表单；当前清单与已输入文字一并带入。 */
    fun onOpenFullManualForm() {
        val s = _state.value
        if (s.busy) return
        val target = (s.selectedView as? TodoView.CustomList)?.listId
        _state.value = s.copy(
            detail = DetailDraft.forCreation(s.draftText, target, s.draftNote),
            inputError = null,
            message = null,
            voiceTextEditing = false,
        )
    }

    /** 把当前识别文字带入已有事项详情，由用户补全字段后创建。 */
    fun onOpenManualCreate() {
        cancelAutoSave()
        val s = _state.value
        if (s.draftText.isBlank()) {
            _state.value = s.copy(inputError = "请先输入或识别事项文字")
            return
        }
        _state.value = s.copy(
            detail = DetailDraft.forCreation(s.draftText, s.addTargetListId, s.draftNote),
            inputError = null,
            message = null,
            voiceTextEditing = false,
        )
    }

    /** 用户手选一个未来时间。 */
    fun onPickTime(hour: Int, minute: Int) {
        val now = LocalDateTime.ofInstant(clock.now(), zoneId)
        var at = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        if (!at.isAfter(now)) at = at.plusDays(1)
        _state.value = _state.value.copy(draftHasTime = true, draftTime = at.atZone(zoneId).toInstant())
    }

    fun onDraftNoteChange(note: String) {
        _state.value = _state.value.copy(draftNote = note, inputError = null)
    }

    fun onSave() = save(withoutReminder = false)
    fun onSaveWithoutReminder() = save(withoutReminder = true)

    private fun save(withoutReminder: Boolean) {
        cancelAutoSave()
        if (_state.value.speechState != SpeechUiState.Idle) {
            cancelSpeechResources()
            _state.value = _state.value.copy(
                speechState = SpeechUiState.Idle,
                speechPartialText = "",
            )
        }
        val s = _state.value
        val at = if (withoutReminder) null else s.draftTime
        if (!withoutReminder && (!s.draftHasTime || at == null)) {
            _state.value = s.copy(message = "请先选择提醒时间")
            return
        }
        action {
            val result = useCases.confirmDraft(
                s.draftText, at, listId = s.addTargetListId, note = s.draftNote,
            )
            _state.value = _state.value.copy(
                addOpen = false, addTargetListId = null,
                draftText = "", draftNote = "", draftHasTime = false, draftTime = null,
                inputError = null,
                speechState = SpeechUiState.Idle,
                speechPartialText = "",
                voicePanelOpen = false,
                voiceElapsedMs = 0,
                voiceWaveform = emptyList(),
                voiceHasSpoken = false,
                voiceTextEditing = false,
                voiceLimitReached = false,
                voiceDiscardConfirmVisible = false,
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
        val speech = runCatching { speechSettingsStore.load() }.getOrNull()
        _state.value = _state.value.copy(
            aiSettingsForm = AiSettingsForm(
                baseUrl = saved?.baseUrl.orEmpty(),
                model = saved?.model.orEmpty(),
                apiKey = saved?.apiKey.orEmpty(),
                speechApiKey = speech?.apiKey.orEmpty(),
                speechResourceId = speech?.resourceId
                    ?: DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
            ),
            aiConfigured = saved != null,
            speechConfigured = speech != null,
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

    fun onSpeechSettingsChange(field: SpeechSettingsField, value: String) {
        val form = _state.value.aiSettingsForm ?: return
        _state.value = _state.value.copy(
            aiSettingsForm = when (field) {
                SpeechSettingsField.ApiKey ->
                    form.copy(speechApiKey = value, speechError = null)
                SpeechSettingsField.ResourceId ->
                    form.copy(speechResourceId = value, speechError = null)
            }
        )
    }

    fun onSaveSpeechSettings() {
        val form = _state.value.aiSettingsForm ?: return
        val existing = runCatching { speechSettingsStore.load() }.getOrNull()
        val clean = try {
            DoubaoSpeechSettings(
                apiKey = form.speechApiKey,
                resourceId = form.speechResourceId,
                uploadAcknowledged = existing?.uploadAcknowledged ?: false,
            ).validate()
        } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(
                aiSettingsForm = form.copy(speechError = e.message),
            )
            return
        }
        try {
            speechSettingsStore.save(clean)
            _state.value = _state.value.copy(
                aiSettingsForm = form.copy(
                    speechApiKey = clean.apiKey,
                    speechResourceId = clean.resourceId,
                    speechError = null,
                ),
                speechConfigured = true,
                message = "豆包语音配置已保存（密钥已加密存储，不进入日志或备份）",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                aiSettingsForm = form.copy(speechError = "语音配置保存失败，请重试"),
            )
        }
    }

    fun onClearSpeechSettings() {
        try {
            speechSettingsStore.clear()
            _state.value = _state.value.copy(
                aiSettingsForm = _state.value.aiSettingsForm?.copy(
                    speechApiKey = "",
                    speechResourceId = DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
                    speechError = null,
                ),
                speechConfigured = false,
                message = "已清除豆包语音配置；仍可手动添加事项",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                aiSettingsForm = _state.value.aiSettingsForm?.copy(
                    speechError = "清除语音配置失败，请重试",
                ),
            )
        }
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
        cancelAutoSave()
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
        launchOrganize(current.draftText, settings, auto = false)
    }

    private fun autoOrganizeAfterSpeech(source: String) {
        if (_state.value.aiBusy) return
        val settings = runCatching { aiSettingsStore.load() }.getOrNull() ?: return
        launchOrganize(source, settings, auto = true)
    }

    /** 点倒计时或任何退出/编辑动作都取消本次自动保存；取消后不再自动恢复。 */
    fun onCancelAutoSave() {
        if (_state.value.autoSaveDeadline == null) return
        cancelAutoSave()
        _state.value = _state.value.copy(message = "已取消自动保存；请核对后手动保存")
    }

    /** 尝试切换 tab 时立即停止录音并取消自动保存，随后由界面确认是否放弃。 */
    fun onTabSwitchAttempt() {
        cancelAutoSave()
        if (_state.value.speechState == SpeechUiState.Recording ||
            _state.value.speechState == SpeechUiState.Connecting
        ) {
            onStopSpeech()
        }
    }

    /** 切到后台或锁屏：停止录音、取消自动保存，已获得的文字保留。 */
    fun onAppBackgrounded() {
        cancelAutoSave()
        if (_state.value.speechState == SpeechUiState.Recording ||
            _state.value.speechState == SpeechUiState.Connecting
        ) {
            onStopSpeech()
        }
    }

    internal fun onAutoSaveTick() {
        val deadline = _state.value.autoSaveDeadline ?: return
        val remaining = (deadline.toEpochMilli() - clock.now().toEpochMilli()).coerceAtLeast(0)
        if (remaining <= 0) {
            performAutoSave()
        } else {
            _state.update {
                if (it.autoSaveDeadline == deadline) {
                    it.copy(autoSaveRemainingMs = remaining)
                } else {
                    it
                }
            }
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        val deadline = clock.now().plusMillis(AUTO_SAVE_DELAY_MS)
        _state.value = _state.value.copy(
            autoSaveDeadline = deadline,
            autoSaveRemainingMs = AUTO_SAVE_DELAY_MS,
        )
        autoSaveJob = viewModelScope.launch {
            while (isActive) {
                delay(AUTO_SAVE_TICK_MS)
                onAutoSaveTick()
                if (_state.value.autoSaveDeadline == null) break
            }
        }
    }

    private fun cancelAutoSave() {
        autoSaveSuppressed = true
        autoSaveJob?.cancel()
        autoSaveJob = null
        _state.update {
            it.copy(autoSaveDeadline = null, autoSaveRemainingMs = 0)
        }
    }

    internal fun onUndoTick() {
        val pending = _state.value.pendingUndo ?: return
        if (clock.now().isBefore(pending.deadline)) return
        _state.update {
            if (it.pendingUndo?.deadline == pending.deadline) it.copy(pendingUndo = null) else it
        }
    }

    private fun startUndoWindow(itemIds: List<String>, drafts: List<AiDraft>) {
        if (itemIds.isEmpty() || drafts.isEmpty()) return
        undoJob?.cancel()
        val deadline = clock.now().plusMillis(UNDO_WINDOW_MS)
        _state.update {
            it.copy(pendingUndo = PendingBatchUndo(itemIds, drafts, deadline))
        }
        undoJob = viewModelScope.launch {
            while (isActive) {
                delay(UNDO_TICK_MS)
                onUndoTick()
                if (_state.value.pendingUndo == null) break
            }
        }
    }

    /** 五秒内撤回本次整批添加；内容恢复为草稿，必须由用户手动保存。 */
    fun onUndoBatch() {
        val pending = _state.value.pendingUndo ?: return
        if (_state.value.busy) return
        if (!clock.now().isBefore(pending.deadline)) {
            onUndoTick()
            return
        }
        undoJob?.cancel()
        undoJob = null
        action {
            try {
                useCases.undoBatchCreation(pending.itemIds)
            } catch (e: Exception) {
                _state.update { it.copy(pendingUndo = null) }
                throw e
            }
            autoSaveSuppressed = true
            _state.value = _state.value.copy(
                pendingUndo = null,
                aiDrafts = pending.drafts,
                selectedAiDraftId = pending.drafts.firstOrNull()?.itemId,
                aiError = null,
                autoSaveDeadline = null,
                autoSaveRemainingMs = 0,
                voicePanelOpen = false,
                voiceElapsedMs = 0,
                voiceWaveform = emptyList(),
                voiceHasSpoken = false,
                voiceTextEditing = false,
                voiceLimitReached = false,
                voiceDiscardConfirmVisible = false,
                message = "已撤销本次添加，内容已恢复为草稿；请核对后手动保存",
            )
            reload()
        }
    }

    private fun performAutoSave() {
        if (_state.value.busy || _state.value.autoSaveDeadline == null) return
        performAutoSaveOnce()
    }

    private fun performAutoSaveOnce() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        _state.update { it.copy(autoSaveDeadline = null, autoSaveRemainingMs = 0) }
        if (!aiDraftsReadyForAutoSave()) {
            _state.update {
                it.copy(
                    aiError = "草稿包含需要修正的字段，已暂停自动保存；请修改后手动保存",
                )
            }
            return
        }
        val inputs = buildAiInputs(forceInbox = false) ?: return
        saveAiInputs(inputs, automatic = true)
    }

    /** 自动保存前用可见草稿重新校验，任何阻塞或无效字段都不进入数据库。 */
    private fun aiDraftsReadyForAutoSave(): Boolean {
        val drafts = _state.value.aiDrafts
        if (drafts.isEmpty()) return false
        if (drafts.any {
                it.title.isBlank() || it.requiresNoReminderAcknowledgement || it.recurrenceNeedsTime
            }
        ) {
            return false
        }
        val today = clock.now().atZone(zoneId).toLocalDate()
        return runCatching {
            drafts.forEach { draft ->
                val edit = draft.toTodoEdit(zoneId)
                require(edit.title.isNotBlank()) { "标题不能为空" }
                edit.reminders.orEmpty().forEach { reminder ->
                    reminder.at?.let { require(it.isAfter(clock.now())) }
                }
                draft.recurrence?.toRule(edit.plannedDate ?: today)
            }
        }.isSuccess
    }

    private fun launchOrganize(source: String, settings: AiSettings, auto: Boolean) {
        aiGeneration++
        aiJob?.cancel()
        val generation = aiGeneration
        val lists = _state.value.todoLists
        _state.value = _state.value.copy(
            aiBusy = true, aiError = null, inputError = null, message = null,
        )
        aiJob = viewModelScope.launch {
            try {
                val drafts = aiClient.organizeDrafts(
                    source,
                    AiPromptContext(
                        now = clock.now(),
                        zoneId = zoneId,
                        listNames = lists.map { it.name },
                    ),
                    settings,
                )
                require(drafts.isNotEmpty()) { "AI 没有返回事项草稿；文字已保留" }
                require(drafts.map { it.itemId }.distinct().size == drafts.size) { "AI 草稿身份重复" }
                if (generation == aiGeneration) {
                    val resolved = resolveAiDrafts(drafts, source, lists)
                    val autoReady = auto && !autoSaveSuppressed &&
                        !_state.value.voiceLimitReached
                    _state.value = _state.value.copy(
                        aiBusy = false,
                        aiDrafts = resolved,
                        selectedAiDraftId = resolved.first().itemId,
                    )
                    val ready = autoReady && aiDraftsReadyForAutoSave()
                    _state.value = _state.value.copy(
                        aiError = if (autoReady && !ready) {
                            "草稿包含需要修正的字段，已暂停自动保存；请修改后手动保存"
                        } else null,
                        message = if (ready) {
                            "已自动整理为 ${resolved.size} 条草稿；2 秒后自动保存"
                        } else if (auto) {
                            "已自动整理为 ${resolved.size} 条草稿；请核对后手动保存"
                        } else null,
                    )
                    if (ready) scheduleAutoSave()
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

    private fun resolveAiDrafts(
        drafts: List<AiDraft>,
        source: String,
        lists: List<TodoList>,
    ): List<AiDraft> = drafts.map { draft ->
        val requested = draft.listName.trim()
        val matched = requested.takeIf { it.isNotEmpty() }
            ?.let { name -> lists.firstOrNull { it.name.trim() == name } }
        val missing = requested.isNotBlank() && matched == null
        val noReminder = draft.reminders.isEmpty() && draft.recurrence == null
        draft.copy(
            sourceText = source,
            listId = if (noReminder) null else matched?.id,
            listNotice = if (missing) "未找到清单「$requested」，已暂存收集箱" else null,
        )
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
    fun onAiDraftNoteChange(note: String) = updateAiDraft { it.copy(note = note) }
    fun onAiDraftListChange(listId: String?) = updateAiDraft {
        it.copy(listId = listId, listName = "")
    }
    fun onAiDraftPlannedDateChange(value: String) = updateAiDraft { it.copy(plannedDate = value) }
    fun onAiDraftDueChange(value: String) = updateAiDraft { it.copy(dueAt = value) }
    fun onAiDraftQuadrantChange(quadrant: Quadrant) = updateAiDraft { it.copy(quadrant = quadrant) }
    fun onAiDraftClarificationAck(checked: Boolean) = updateAiDraft {
        it.copy(
            noReminderAcknowledged = checked,
            reminders = if (checked) emptyList() else it.reminders,
            recurrence = if (checked) null else it.recurrence,
        )
    }

    private fun updateAiDraft(block: (AiDraft) -> AiDraft) {
        if (_state.value.busy) return
        val draft = _state.value.aiDraft ?: return
        cancelAutoSave()
        _state.value = _state.value.copy(
            aiDrafts = _state.value.aiDrafts.map {
                if (it.itemId == draft.itemId) block(it) else it
            },
            aiError = null,
        )
    }

    fun onSelectAiDraft(itemId: String) {
        if (!_state.value.busy && _state.value.aiDrafts.any { it.itemId == itemId }) {
            cancelAutoSave()
            _state.value = _state.value.copy(selectedAiDraftId = itemId, aiError = null)
        }
    }

    fun onDeleteAiDraft(itemId: String) {
        if (_state.value.busy) return
        cancelAutoSave()
        val remaining = _state.value.aiDrafts.filter { it.itemId != itemId }
        _state.value = _state.value.copy(
            aiDrafts = remaining,
            selectedAiDraftId = _state.value.selectedAiDraftId
                ?.takeIf { id -> remaining.any { it.itemId == id } }
                ?: remaining.firstOrNull()?.itemId,
            aiError = null,
            message = if (remaining.isEmpty()) "已删除全部草稿，文字已保留" else null,
        )
    }

    fun onAiRecurrenceChange(frequency: RecurrenceFrequency?) = updateAiDraft { draft ->
        if (draft.recurrence?.frequency == frequency) return@updateAiDraft draft
        val today = clock.now().atZone(zoneId).toLocalDate()
        draft.copy(recurrence = frequency?.let { kind ->
            AiRecurrenceDraft(
                frequency = kind,
                weekdays = if (kind == RecurrenceFrequency.Weekly) setOf(today.dayOfWeek)
                else emptySet(),
                monthDay = if (kind == RecurrenceFrequency.Monthly) today.dayOfMonth.toString()
                else "",
                time = draft.recurrence?.time?.takeIf { it.isNotBlank() } ?: "09:00",
            )
        })
    }

    fun onAiRecurrenceWeekdayToggle(day: DayOfWeek) = updateAiDraft { draft ->
        val rule = draft.recurrence ?: return@updateAiDraft draft
        draft.copy(recurrence = rule.copy(
            weekdays = if (day in rule.weekdays) rule.weekdays - day else rule.weekdays + day,
        ))
    }

    fun onAiRecurrenceMonthDayChange(value: String) = updateAiDraft { draft ->
        draft.copy(recurrence = draft.recurrence?.copy(monthDay = value))
    }

    fun onAiRecurrenceTimeChange(value: String) = updateAiDraft { draft ->
        draft.copy(recurrence = draft.recurrence?.copy(time = value))
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
        cancelAutoSave()
        _state.value = _state.value.copy(
            aiDrafts = emptyList(),
            selectedAiDraftId = null,
            aiError = null,
            message = "已取消 AI 草稿，文字已保留，可继续编辑或手动创建",
        )
    }

    fun onConfirmAiDraft() {
        cancelAutoSave()
        val inputs = buildAiInputs(forceInbox = false) ?: return
        saveAiInputs(inputs)
    }

    fun onConfirmAiDraftAsInbox() {
        cancelAutoSave()
        val inputs = buildAiInputs(forceInbox = true) ?: return
        saveAiInputs(inputs)
    }

    private fun buildAiInputs(forceInbox: Boolean): List<TodoDraftInput>? {
        if (_state.value.busy || _state.value.aiDrafts.isEmpty()) return null
        val today = clock.now().atZone(zoneId).toLocalDate()
        val inputs = mutableListOf<TodoDraftInput>()
        for ((index, draft) in _state.value.aiDrafts.withIndex()) {
            try {
                if (forceInbox) {
                    require(draft.title.isNotBlank()) { "标题不能为空" }
                    val plannedDate = draft.plannedDate.trim().takeIf { it.isNotEmpty() }
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val edit = TodoEdit(
                        title = draft.title.trim(),
                        plannedDate = plannedDate,
                        dueAt = null,
                        remindAt = null,
                        reminders = emptyList(),
                        listId = null,
                        updateList = true,
                        note = draft.note,
                    )
                    inputs += TodoDraftInput(
                        itemId = draft.itemId,
                        edit = edit,
                        quadrant = draft.quadrant,
                        suggestedQuadrant = draft.suggestedQuadrant,
                        recurrence = null,
                    )
                } else {
                    require(!draft.requiresNoReminderAcknowledgement) {
                        "时间还不明确：请补充提醒时间，或勾选“确认不设提醒”"
                    }
                    val noReminder = draft.reminders.isEmpty() && draft.recurrence == null
                    val listId = if (noReminder) null else draft.listId
                    val edit = draft.toTodoEdit(zoneId).copy(listId = listId, updateList = true)
                    require(edit.title.isNotBlank()) { "标题不能为空" }
                    edit.reminders.orEmpty().forEach { reminder ->
                        reminder.at?.let {
                            require(it.isAfter(clock.now())) { "提醒时间必须晚于当前时间" }
                        }
                    }
                    inputs += TodoDraftInput(
                        itemId = draft.itemId,
                        edit = edit,
                        quadrant = draft.quadrant,
                        suggestedQuadrant = draft.suggestedQuadrant,
                        recurrence = draft.recurrence?.toRule(edit.plannedDate ?: today),
                    )
                }
            } catch (e: IllegalArgumentException) {
                _state.value = _state.value.copy(
                    selectedAiDraftId = draft.itemId,
                    aiError = "第 ${index + 1} 条：${e.message ?: "草稿字段无效，请检查"}",
                )
                return null
            }
        }
        return inputs
    }

    private fun saveAiInputs(inputs: List<TodoDraftInput>, automatic: Boolean = false) {
        cancelAutoSave()
        val savedDrafts = _state.value.aiDrafts
        action {
            val results = useCases.confirmDrafts(inputs)
            _state.value = _state.value.copy(
                addOpen = false,
                addTargetListId = null,
                aiDrafts = emptyList(),
                selectedAiDraftId = null,
                aiError = null,
                draftText = "",
                draftHasTime = false,
                draftTime = null,
                inputError = null,
                message = (if (automatic) "已自动保存 ${results.size} 条事项。"
                else "已保存 ${results.size} 条事项。") + when {
                    results.any { it.outcome == ReminderOutcome.NotReady } ->
                        "部分提醒未就绪，请检查权限后重试"
                    results.any { it.outcome == ReminderOutcome.Scheduled } -> "已安排明确设置的提醒"
                    else -> "未设置提醒"
                },
                voicePanelOpen = false,
                voiceElapsedMs = 0,
                voiceWaveform = emptyList(),
                voiceHasSpoken = false,
                voiceTextEditing = false,
                voiceLimitReached = false,
                voiceDiscardConfirmVisible = false,
                autoSaveDeadline = null,
                autoSaveRemainingMs = 0,
            )
            startUndoWindow(results.map { it.item.id }, savedDrafts)
            reload()
        }
    }

    fun onDelete(id: String) {
        val item = (_state.value.items + _state.value.completedItems).firstOrNull { it.id == id }
        if (item?.seriesId != null && item.completedAt == null) {
            _state.value = _state.value.copy(pendingRecurringDeleteId = id, message = null)
            return
        }
        action {
            useCases.deleteItem(id, RecurringDeleteScope.ThisOnly)
            _state.value = _state.value.copy(message = "已移到回收站，可在 30 天内恢复")
            reload()
        }
    }

    fun onConfirmRecurringDelete(scope: RecurringDeleteScope) {
        val id = _state.value.pendingRecurringDeleteId ?: return
        _state.value = _state.value.copy(pendingRecurringDeleteId = null)
        action {
            useCases.deleteItem(id, scope)
            _state.value = _state.value.copy(
                message = when (scope) {
                    RecurringDeleteScope.ThisOnly -> "本次实例已移到回收站，后续重复安排不变"
                    RecurringDeleteScope.ThisAndFuture -> "本次及以后的未完成实例已移到回收站，重复安排已停止"
                },
            )
            reload()
        }
    }

    fun onCancelRecurringDelete() {
        _state.value = _state.value.copy(pendingRecurringDeleteId = null)
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

    fun onDetailNoteChange(note: String) {
        if (!_state.value.busy) _state.value = _state.value.copy(detail = _state.value.detail?.copy(note = note))
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

    /** 折叠/展开同一重复系列的积压实例；只影响列表展示。 */
    fun onToggleBacklog(seriesId: String) {
        if (_state.value.busy) return
        val current = _state.value.expandedBacklogSeries
        _state.value = _state.value.copy(
            expandedBacklogSeries = if (seriesId in current) current - seriesId else current + seriesId,
        )
    }

    /** 重复实例：仅修改本次，后续实例维持旧规则。 */
    fun onSaveDetailThisOnly() = saveSeriesDetail(RecurringEditScope.ThisOnly)

    /** 重复实例：从本次起修改后续实例，历史完成/漏做记录不变。 */
    fun onSaveDetailThisAndFuture() = saveSeriesDetail(RecurringEditScope.ThisAndFuture)

    private fun saveSeriesDetail(scope: RecurringEditScope) {
        val detail = _state.value.detail ?: return
        val original = detail.original ?: return
        action {
            val edit = detail.edit(zoneId)
            useCases.updateRecurringItem(original.id, edit, scope)
            val message = when (scope) {
                RecurringEditScope.ThisOnly -> "已仅修改本次实例，后续实例仍按原规则"
                RecurringEditScope.ThisAndFuture -> "已从本次起修改后续实例，过去的完成与漏做记录保持不变"
            }
            _state.value = _state.value.copy(detail = null, message = message)
            reload()
        }
    }

    fun onSaveDetail() {
        val detail = _state.value.detail ?: return
        action {
            val edit = detail.edit(zoneId)
            val original = detail.original
            val message = if (original == null) {
                if (detail.repeat != RepeatDraft.None) {
                    val result = useCases.createRecurring(
                        edit.title, recurrenceRule(detail), edit.listId, note = edit.note.orEmpty(),
                    )
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
                        note = edit.note.orEmpty(),
                    )
                    _state.value = _state.value.copy(
                        addOpen = false,
                        addTargetListId = null,
                        draftText = "",
                        draftNote = "",
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
        val updated = useCases.setPersistentRing(id, enabled)
        updateDetailItem(id, updated)
        _state.value = _state.value.copy(
            message = if (enabled) "已改为持续响铃（默认 60 秒后停止并保留通知）" else "已改为普通提示音与振动",
        )
        reload()
    }

    /** 详情页开启/关闭到点朗读。 */
    fun onSetReadAloud(id: String, enabled: Boolean) = action {
        val updated = useCases.setReadAloud(id, enabled)
        updateDetailItem(id, updated)
        _state.value = _state.value.copy(
            message = if (enabled) "已开启朗读；到点用本机语音播报标题" else "已关闭朗读",
        )
        reload()
    }

    /** 锁屏朗读必须由用户明确开启；关闭后锁屏只响提示音。 */
    fun onSetReadAloudWhenLocked(id: String, enabled: Boolean) = action {
        val updated = useCases.setReadAloudWhenLocked(id, enabled)
        updateDetailItem(id, updated)
        _state.value = _state.value.copy(
            message = if (enabled) "已允许锁屏朗读事项正文" else "锁屏时只响提示音，不朗读正文",
        )
        reload()
    }

    private fun updateDetailItem(id: String, item: TodoItem) {
        val detail = _state.value.detail ?: return
        if (detail.original?.id != id) return
        _state.value = _state.value.copy(detail = detail.copy(original = item))
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

    override fun onCleared() {
        aiGeneration++
        aiJob?.cancel()
        autoSaveJob?.cancel()
        undoJob?.cancel()
        cancelSpeechResources()
        super.onCleared()
    }

    private companion object {
        const val VOICE_TICK_MS = 100L
        const val SILENCE_AFTER_SPEECH_MS = 2_000L
        const val NO_SPEECH_TIMEOUT_MS = 10_000L
        const val MAX_RECORDING_MS = 60_000L
        const val WAVEFORM_BARS = 36
        const val SPEECH_LEVEL = 0.035f
        const val AUTO_SAVE_DELAY_MS = 2_000L
        const val AUTO_SAVE_TICK_MS = 100L
        const val UNDO_WINDOW_MS = 5_000L
        const val UNDO_TICK_MS = 100L
    }
}

internal fun pcmLevel(pcm: ByteArray): Float {
    val samples = pcm.size / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (index in 0 until samples) {
        val low = pcm[index * 2].toInt() and 0xff
        val high = pcm[index * 2 + 1].toInt()
        val sample = (high shl 8) or low
        sum += sample.toDouble() * sample
    }
    return (kotlin.math.sqrt(sum / samples) / 32768.0).toFloat()
}
