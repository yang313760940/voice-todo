package com.voicetodo.domain

import com.voicetodo.settings.AppPreferences
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val BACKUP_FORMAT = "voice-todo-backup"
const val BACKUP_FORMAT_VERSION = 1

/**
 * 备份的业务内容：事项（含重复模板与实例）、清单、专注历史、番茄设置和外观偏好。
 * 录音、AI 服务配置/密钥、权限授权不属于备份数据。
 */
data class BackupData(
    val items: List<TodoItem>,
    val lists: List<TodoList>,
    val focusSessions: List<FocusSession>,
    val focusSettings: FocusSettings,
    val preferences: AppPreferences,
)

data class BackupCounts(
    val itemCount: Int,
    val listCount: Int,
    val focusSessionCount: Int,
    val recurringSeriesCount: Int,
)

data class BackupDocument(
    val formatVersion: Int,
    val exportedAt: Instant,
    val appVersion: String,
    val data: BackupData,
) {
    fun counts(): BackupCounts = BackupCounts(
        itemCount = data.items.size,
        listCount = data.lists.size,
        focusSessionCount = data.focusSessions.size,
        recurringSeriesCount = data.items.count { it.recurrence != null },
    )

    fun preview(): BackupPreview = BackupPreview(
        formatVersion = formatVersion,
        exportedAt = exportedAt,
        appVersion = appVersion,
        counts = counts(),
    )
}

data class BackupPreview(
    val formatVersion: Int,
    val exportedAt: Instant,
    val appVersion: String,
    val counts: BackupCounts,
)

data class BackupExport(
    val json: String,
    val suggestedFileName: String,
    val preview: BackupPreview,
)

data class RestoreCopyInfo(
    val name: String,
    val createdAt: Instant,
    val sizeBytes: Long,
)

data class RestoreOutcome(
    val preview: BackupPreview,
    val restoreCopyName: String,
    val remindersRebuilt: Boolean,
    val warning: String? = null,
)

/** 备份文件结构损坏、字段非法或版本不兼容；调用方必须保持原数据不变。 */
class BackupRejectedException(message: String) : IllegalArgumentException(message)

interface BackupCodec {
    fun encode(data: BackupData, exportedAt: Instant, appVersion: String): String
    fun decode(text: String): BackupDocument
}

interface BackupStore {
    suspend fun snapshot(preferences: AppPreferences): BackupData

    /** 在一个事务内替换业务数据；[BackupData.preferences] 由调用方单独保存。 */
    suspend fun replace(data: BackupData)
}

interface BackupFileStore {
    fun writeRestoreCopy(json: String, at: Instant, zone: ZoneId): String
    fun listRestoreCopies(): List<RestoreCopyInfo>
    fun readRestoreCopy(name: String): String
}

interface BackupManager {
    fun suggestedFileName(): String
    suspend fun export(): BackupExport
    suspend fun preview(text: String): BackupPreview
    suspend fun replace(text: String): RestoreOutcome
    suspend fun previewRestoreCopy(name: String): BackupPreview
    suspend fun replaceRestoreCopy(name: String): RestoreOutcome
    fun restoreCopies(): List<RestoreCopyInfo>
}

/**
 * 备份恢复的应用用例入口。
 *
 * 替换顺序刻意固定为：校验新文件 → 生成当前数据恢复副本 → 保存偏好 → 取消旧调度
 * → 事务替换数据库 → 按新数据对账。任一步失败都会回滚偏好并重新对账旧数据，
 * 且旧数据在成功替换前不会被删除。
 */
class DataBackupUseCases(
    private val store: BackupStore,
    private val codec: BackupCodec,
    private val files: BackupFileStore,
    private val scheduler: ReminderScheduler,
    private val focusScheduler: FocusScheduler,
    private val loadPreferences: () -> AppPreferences,
    private val savePreferences: (AppPreferences) -> Unit,
    private val clock: Clock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val appVersion: String = "",
    private val reconcile: suspend () -> Unit = {},
    private val exclusive: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) : BackupManager {
    private val mutations = Mutex()

    override fun suggestedFileName(): String = fileName(clock.now())

    override suspend fun export(): BackupExport = mutations.withLock {
        val now = clock.now()
        val data = store.snapshot(loadPreferences()).withoutActiveSessions(now)
        val document = BackupDocument(
            formatVersion = BACKUP_FORMAT_VERSION,
            exportedAt = now,
            appVersion = appVersion,
            data = data,
        )
        BackupExport(
            json = codec.encode(data, now, appVersion),
            suggestedFileName = fileName(now),
            preview = document.preview(),
        )
    }

    override suspend fun preview(text: String): BackupPreview = codec.decode(text).preview()

    override suspend fun replace(text: String): RestoreOutcome = mutations.withLock {
        val now = clock.now()
        val document = codec.decode(text)
        val next = document.data.withoutActiveSessions(now)
        var restoreName = ""
        var previousPreferences: AppPreferences? = null
        var preferencesChanged = false
        var schedulesCancelled = false
        try {
            exclusive {
                val priorPreferences = loadPreferences()
                val current = store.snapshot(priorPreferences)
                // 恢复副本必须先落盘；写不成功就不允许进入任何破坏性步骤。
                restoreName = files.writeRestoreCopy(
                    codec.encode(current, now, appVersion),
                    now,
                    zone(),
                )
                previousPreferences = priorPreferences
                if (previousPreferences != next.preferences) {
                    savePreferences(next.preferences)
                    preferencesChanged = true
                }
                current.items.forEach { scheduler.cancel(it.id) }
                current.focusSessions.filter { it.isActive() }.forEach { focusScheduler.cancel(it.id) }
                schedulesCancelled = true
                store.replace(next)
            }
        } catch (error: Exception) {
            if (preferencesChanged) {
                previousPreferences?.let { runCatching { savePreferences(it) } }
            }
            if (schedulesCancelled) runCatching { reconcile() }
            throw error
        }

        val rebuildError = runCatching { reconcile() }.exceptionOrNull()
        RestoreOutcome(
            preview = document.preview(),
            restoreCopyName = restoreName,
            remindersRebuilt = rebuildError == null,
            warning = rebuildError?.let {
                "数据已恢复，但提醒重建失败：${it.message ?: "未知错误"}"
            },
        )
    }

    override suspend fun previewRestoreCopy(name: String): BackupPreview =
        preview(files.readRestoreCopy(name))

    override suspend fun replaceRestoreCopy(name: String): RestoreOutcome =
        replace(files.readRestoreCopy(name))

    override fun restoreCopies(): List<RestoreCopyInfo> = files.listRestoreCopies()

    private fun fileName(now: Instant): String =
        "voice-todo-backup-${FILE_TIME.format(now.atZone(zone()))}.json"

    /**
     * 备份快照不携带“正在运行”的计时状态：导出和导入都把它冻结为已结束的记录，
     * 保留实际累计时长与历史，但不会在另一台设备上继续走秒。
     */
    private fun BackupData.withoutActiveSessions(now: Instant): BackupData =
        copy(focusSessions = focusSessions.map { it.frozenForBackup(now) })

    private fun FocusSession.frozenForBackup(now: Instant): FocusSession {
        if (!isActive()) return this
        val stoppedAt = when {
            state == FocusSessionState.EndedPendingConfirmation -> endedAt ?: endsAt
            now.isBefore(endsAt) -> now
            else -> endsAt
        }
        val accumulated = when (state) {
            FocusSessionState.Running -> (
                accumulatedFocusSeconds +
                    Duration.between(lastResumedAt, stoppedAt).seconds.coerceAtLeast(0)
                ).coerceAtMost(durationSeconds)
            FocusSessionState.Paused -> accumulatedFocusSeconds.coerceAtMost(durationSeconds)
            FocusSessionState.EndedPendingConfirmation -> durationSeconds
            else -> accumulatedFocusSeconds
        }
        return copy(
            state = FocusSessionState.Abandoned,
            accumulatedFocusSeconds = accumulated,
            completedPomodoros = 0,
            endedAt = stoppedAt,
            pausedAt = null,
        )
    }

    private companion object {
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
