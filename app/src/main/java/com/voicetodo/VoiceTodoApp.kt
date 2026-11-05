package com.voicetodo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.voicetodo.alarm.SystemReminderScheduler
import com.voicetodo.alarm.SystemFocusScheduler
import com.voicetodo.alarm.ReminderRecoveryCoordinator
import com.voicetodo.data.FileTodoRepository
import androidx.room.Room
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.data.RoomFocusRepository
import com.voicetodo.domain.SystemClock
import com.voicetodo.domain.TodoUseCases
import com.voicetodo.domain.FocusUseCases
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        val db = Room.databaseBuilder(this, TodoDatabase::class.java, "todos.db")
            .addMigrations(TodoDatabase.MIGRATION_3_4, TodoDatabase.MIGRATION_5_6)
            .build()

        repository = RoomTodoRepository(
            db,
            FileTodoRepository(filesDir, ZoneId.systemDefault()),
        )
        focusRepository = RoomFocusRepository(db)
        scheduler = SystemReminderScheduler(this)
        focusScheduler = SystemFocusScheduler(this)
        useCases = TodoUseCases(repository, scheduler, SystemClock())
        focusUseCases = FocusUseCases(focusRepository, focusScheduler, SystemClock(), repository, ZoneId.systemDefault())
        reminderRecovery = ReminderRecoveryCoordinator(this, useCases)

        // 冷启动/重开时对账：把仓库中仍有效的未来提醒重新排入系统（幂等）。
        appScope.launch {
            try {
                reminderRecovery.reconcile()
                focusUseCases.reconcileEnd()
            } catch (e: Exception) {
                android.util.Log.e("VoiceTodoApp", "Migration/reconcile failed; legacy data retained", e)
            }
        }
    }

    fun requestReminderReconcile() {
        appScope.launch {
            runCatching { reminderRecovery.reconcile() }
                .onFailure { android.util.Log.e("VoiceTodoApp", "Reminder reconcile failed", it) }
        }
    }

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
