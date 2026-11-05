package com.voicetodo.alarm

import android.app.Activity
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 到点全屏提醒界面（最小实现，无 Compose 依赖）：
 * 展示标题，提供“完成 / 延后 / 关闭”三种动作。
 *
 * - 完成：完成事项并取消本实例未来提醒。
 * - 延后：10 分钟、30 分钟、1 小时或自选时刻，实际重新登记系统闹钟，不改截止与下一周期。
 * - 关闭：只结束本次提醒，不完成事项。
 * 动作经 [AlarmActionReceiver] 广播交给 [com.voicetodo.domain.TodoUseCases]，重复点击不会重复调度。
 */
class AlarmRingActivity : Activity() {

    private lateinit var body: TextView
    private var reminderTitle: String = "提醒"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID) ?: "unknown"
        val reminderId = intent.getStringExtra(SystemReminderScheduler.EXTRA_REMINDER_ID) ?: itemId
        val revision = intent.getLongExtra(SystemReminderScheduler.EXTRA_REVISION, Long.MIN_VALUE)
        reminderTitle = intent.getStringExtra(SystemReminderScheduler.EXTRA_TITLE) ?: "提醒"
        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 3, padding, padding)
        }
        root.addView(TextView(this).apply { text = "提醒"; textSize = 18f })
        body = TextView(this).apply {
            textSize = 30f
            setPadding(0, padding, 0, padding)
        }
        refreshVisibleBody()
        root.addView(body)
        val result = TextView(this).apply { textSize = 16f; setPadding(0, padding, 0, padding) }
        root.addView(result)

        fun dispatch(action: String, extra: ((android.content.Intent) -> Unit)? = null) {
            val stop = android.content.Intent(this@AlarmRingActivity, AlarmActionReceiver::class.java).apply {
                this.action = action
                putExtra(SystemReminderScheduler.EXTRA_ITEM_ID, itemId)
                putExtra(SystemReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                putExtra(SystemReminderScheduler.EXTRA_REVISION, revision)
                extra?.invoke(this)
            }
            sendBroadcast(stop)
            // 兜底：立即停止本实例声音与通知（广播同样会做，幂等）。
            AlarmActionReceiver.stopOngoingReminder(this@AlarmRingActivity, itemId, reminderId)
        }

        fun finishWith(text: String) {
            result.text = text
            root.postDelayed({ finish() }, 600)
        }

        root.addView(Button(this).apply {
            text = "完成"
            setOnClickListener {
                dispatch(AlarmActionReceiver.ACTION_COMPLETE)
                finishWith("已标记完成；本实例未来提醒已取消")
            }
        })
        listOf(
            com.voicetodo.domain.SnoozeOption.TenMinutes to "延后 10 分钟",
            com.voicetodo.domain.SnoozeOption.ThirtyMinutes to "延后 30 分钟",
            com.voicetodo.domain.SnoozeOption.OneHour to "延后 1 小时",
        ).forEach { (option, label) ->
            root.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    dispatch(AlarmActionReceiver.ACTION_SNOOZE) {
                        it.putExtra(AlarmActionReceiver.EXTRA_SNOOZE_MINUTES, option.minutes)
                    }
                    finishWith("已延后 ${option.minutes} 分钟")
                }
            })
        }
        root.addView(Button(this).apply {
            text = "自选延后时间"
            setOnClickListener {
                val now = LocalDateTime.now()
                TimePickerDialog(this@AlarmRingActivity, { _, hour, minute ->
                    var at = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
                    if (!at.isAfter(now)) at = at.plusDays(1)
                    val instant = at.atZone(ZoneId.systemDefault()).toInstant()
                    dispatch(AlarmActionReceiver.ACTION_SNOOZE) {
                        it.putExtra(AlarmActionReceiver.EXTRA_SNOOZE_AT, instant.toString())
                    }
                    finishWith("已延后至 ${at.toLocalTime()}")
                }, now.hour, now.minute, true).show()
            }
        })
        root.addView(Button(this).apply {
            text = "关闭本次提醒"
            setOnClickListener {
                dispatch(AlarmActionReceiver.ACTION_DISMISS)
                finishWith("已关闭本次提醒；事项未完成")
            }
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) refreshVisibleBody()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::body.isInitialized) refreshVisibleBody()
    }

    private fun refreshVisibleBody() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        body.text = reminderBodyForLockState(reminderTitle, locked)
    }
}

internal fun reminderBodyForLockState(title: String, deviceLocked: Boolean): String =
    if (deviceLocked) "解锁后查看事项" else title
