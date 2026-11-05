package com.voicetodo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.voicetodo.alarm.SystemReminderScheduler
import com.voicetodo.alarm.SystemFocusScheduler
import com.voicetodo.alarm.ReminderRecoveryCoordinator
import com.voicetodo.alarm.DeviceProtectedReminderStore
import com.voicetodo.alarm.DirectBootReminderCoordinator
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.JsonBackupCodec
import com.voicetodo.data.PrivateBackupFileStore
import com.voicetodo.data.RoomBackupStore
import androidx.room.Room
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.data.RoomFocusRepository
import com.voicetodo.domain.BackupManager
import com.voicetodo.domain.DataBackupUseCases
import com.voicetodo.domain.SystemClock
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.domain.FocusUseCases
import com.voicetodo.settings.AppPreferencesStore
import java.io.File
import java.time.ZoneId

/**
 * 应用级依赖装配（最小服务定位器，避免引入 DI 框架）。
 * 统一用例入口 [useCases] 供界面与仪器测试共用，测试可注入替身。
 */
class VoiceTodoApp : Application() {

    lateinit var useCases: TodoUseCases
        private set

    lateinit var scheduler: SystemReminderScheduler
        private set

    lateinit var focusScheduler: SystemFocusScheduler
        private set

    lateinit var repository: RoomTodoRepository
        private set

    lateinit var focusRepository: RoomFocusRepository
        private set

    lateinit var focusUseCases: FocusUseCases
        private set

    lateinit var reminderRecovery: ReminderRecoveryCoordinator
        private set

    lateinit var backupManager: BackupManager
        private set

    internal lateinit var directBootStore: DeviceProtectedReminderStore
        private set

    private lateinit var directBootRecovery: DirectBootReminderCoordinator
    private var credentialDependenciesReady = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        directBootStore = DeviceProtectedReminderStore(this)
        scheduler = SystemReminderScheduler(this)
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return
        ensureCredentialDependencies()

        appScope.launch {
            reconcileAfterUnlock()
        }
    }

    @Synchronized
    internal fun ensureCredentialDependencies() {
        if (credentialDependenciesReady) return
        val db = Room.databaseBuilder(this, TodoDatabase::class.java, "todos.db")
            .addMigrations(TodoDatabase.MIGRATION_3_4, TodoDatabase.MIGRATION_5_6)
            .build()
        repository = RoomTodoRepository(
            db,
            FileTodoRepository(filesDir, ZoneId.systemDefault()),
        )
        focusRepository = RoomFocusRepository(db)
        focusScheduler = SystemFocusScheduler(this)
        useCases = TodoUseCases(repository, scheduler, SystemClock())
        focusUseCases = FocusUseCases(focusRepository, focusScheduler, SystemClock(), repository, ZoneId.systemDefault())
        reminderRecovery = ReminderRecoveryCoordinator(this, useCases)
        directBootRecovery = DirectBootReminderCoordinator(
            this,
            directBootStore,
            scheduler,
            useCases,
            reminderRecovery,
        )
        backupManager = DataBackupUseCases(
            store = RoomBackupStore(db, repository),
            codec = JsonBackupCodec,
            files = PrivateBackupFileStore(File(filesDir, PrivateBackupFileStore.directoryName())),
            scheduler = scheduler,
            focusScheduler = focusScheduler,
            loadPreferences = { AppPreferencesStore(this).load() },
            savePreferences = { AppPreferencesStore(this).save(it) },
            clock = SystemClock(),
            zone = { ZoneId.systemDefault() },
            appVersion = appVersion(),
            reconcile = { directBootRecovery.reconcileAfterUnlock() },
            exclusive = { block -> useCases.withMutationLock { block() } },
        )
        credentialDependenciesReady = true
    }

    internal fun restoreBeforeUnlock() {
        directBootRecoveryOrNull()?.restoreBeforeUnlock()
            ?: DirectBootReminderCoordinator.restoreBeforeUnlock(directBootStore, scheduler)
    }

    internal suspend fun reconcileAfterUnlock() {
        ensureCredentialDependencies()
        try {
            directBootRecovery.reconcileAfterUnlock()
            focusUseCases.reconcileEnd()
        } catch (e: Exception) {
            android.util.Log.e("VoiceTodoApp", "Migration/reconcile failed; legacy data retained", e)
        }
    }

    private fun directBootRecoveryOrNull(): DirectBootReminderCoordinator? =
        if (::directBootRecovery.isInitialized) directBootRecovery else null

    fun requestReminderReconcile() {
        appScope.launch {
            runCatching { reconcileAfterUnlock() }
                .onFailure { android.util.Log.e("VoiceTodoApp", "Reminder reconcile failed", it) }
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "未知" }

    companion object {
        const val CHANNEL_REMINDER = "reminder_channel"

        fun createNotificationChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_REMINDER,
                "提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "到点提醒的通知"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
