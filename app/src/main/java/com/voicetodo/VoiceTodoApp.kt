package com.voicetodo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.voicetodo.alarm.SystemReminderScheduler
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.domain.SystemClock
import com.voicetodo.domain.TodoUseCases

/**
 * 应用级依赖装配（最小服务定位器，避免引入 DI 框架）。
 * 统一用例入口 [useCases] 供界面与仪器测试共用，测试可注入替身。
 */
class VoiceTodoApp : Application() {

    lateinit var useCases: TodoUseCases
        private set

    lateinit var scheduler: SystemReminderScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)

        val repo = FileTodoRepository(filesDir)
        scheduler = SystemReminderScheduler(this)
        useCases = TodoUseCases(repo, scheduler, SystemClock())

        // 冷启动/重开时对账：把仓库中仍有效的未来提醒重新排入系统（幂等）。
        scheduler.reconcile(repo.all(), SystemClock().now())
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
