package com.voicetodo.alarm

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 到点全屏提醒界面（最小实现，无 Compose 依赖）：
 * 展示标题，提供“关闭提醒”按钮——关闭即停止响铃并清除通知。
 */
class AlarmRingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: "unknown"
        val title = intent.getStringExtra(SystemReminderScheduler.EXTRA_TITLE) ?: "提醒"

        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 3, padding, padding)
        }
        val label = TextView(this).apply {
            text = "提醒"
            textSize = 18f
        }
        val body = TextView(this).apply {
            text = title
            textSize = 30f
            setPadding(0, padding, 0, padding)
        }
        val close = Button(this).apply {
            text = "关闭提醒"
            setOnClickListener {
                val stop = android.content.Intent(this@AlarmRingActivity, AlarmActionReceiver::class.java).apply {
                    action = AlarmActionReceiver.ACTION_STOP
                    putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
                }
                sendBroadcast(stop)
                // 兜底：直接停止服务与通知
                stopService(android.content.Intent(this@AlarmRingActivity, RingingService::class.java))
                getSystemService(NotificationManager::class.java)
                    .cancel(AlarmReceiver.notificationIdFor(itemId))
                finish()
            }
        }
        root.addView(label)
        root.addView(body)
        root.addView(close)
        setContentView(root)
    }
}
