package com.voicetodo

import android.app.AlarmManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicetodo.alarm.SystemReminderScheduler
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * 验证“真实系统 PendingIntent 闹钟”可用：通过应用统一入口保存一条即将到点的事项，
 * 断言 AlarmManager 确实登记了一个 alarm-clock（setAlarmClock 会建立
 * getNextAlarmClock 可见的系统闹钟），而不是应用内计时器。
 *
 * 需要精确闹钟权限（Android 12+）。若未授予，测试跳过并提示，而不是误报通过。
 */
@RunWith(AndroidJUnit4::class)
class AlarmSchedulingInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Test
    fun savingAnItemRegistersARealSystemAlarmClock() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            assumeTrue("需要 SCHEDULE_EXACT_ALARM 精确闹钟权限", am.canScheduleExactAlarms())
        }

        val app = context.applicationContext as VoiceTodoApp
        // 一条 3 分钟后的提醒
        val at = LocalDateTime.now().plusMinutes(3).withSecond(0).withNano(0)
        val result = app.useCases.confirmDraft("集成测试-闹钟", at)
        assertTrue("应报告已安排", result.scheduled)

        // setAlarmClock 会让系统 nextAlarmClock 指向我们（若更近）
        val next = am.nextAlarmClock
        assertTrue("系统应登记一个 alarm-clock", next != null)
        // 允许少量时钟误差
        val expectedMillis = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val diff = kotlin.math.abs(next!!.triggerTime - expectedMillis)
        assertTrue("系统闹钟触发时刻应接近目标（diff=${diff}ms）", diff < 5_000)

        // 清理
        app.useCases.deleteItem(result.item.id)
    }
}
