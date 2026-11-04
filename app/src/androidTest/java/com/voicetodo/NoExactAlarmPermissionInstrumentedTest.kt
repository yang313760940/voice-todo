package com.voicetodo

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * AC5 回归（设备真机/模拟器）：**撤销精确闹钟权限**后保存，
 * 必须①不崩溃、②事项仍保存、③如实返回 scheduled=false（绝不误报已安排）。
 * 这直接覆盖评审指出的“scheduled 恒为 true + setAlarmClock 未守卫”缺陷。
 */
@RunWith(AndroidJUnit4::class)
class NoExactAlarmPermissionInstrumentedTest {

    private val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
    private val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Before
    fun requireApi31() {
        // 精确闹钟特殊访问从 Android 12/API31 起存在
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
    }

    @After
    fun restore() {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("appops", "set", app.packageName, "SCHEDULE_EXACT_ALARM", "allow"))
            p.waitFor()
        } catch (_: Exception) {
        }
    }

    @Test
    fun withoutExactAlarmPermission_itemSavedButReportsNotScheduled() {
        // 撤销精确闹钟访问
        try {
            val p = Runtime.getRuntime().exec(arrayOf("appops", "set", app.packageName, "SCHEDULE_EXACT_ALARM", "deny"))
            p.waitFor()
        } catch (_: Exception) {
        }
        assumeFalse("需要先成功撤销精确闹钟权限", am.canScheduleExactAlarms())

        val before = runBlocking { app.useCases.listItems().size }
        val at = Instant.now().plusSeconds(300)

        // 不应抛异常
        val result = runBlocking { app.useCases.confirmDraft("无权限回归-${System.currentTimeMillis()}", at) }

        assertTrue("未具备精确闹钟权限时不得谎称已安排", result.outcome == com.voicetodo.domain.ReminderOutcome.NotReady)
        assertTrue("事项仍应保存", runBlocking { app.useCases.listItems().size } >= before + 1)

        runBlocking { app.useCases.deleteItem(result.item.id) }
        assertEquals("清理后应移除", before, runBlocking { app.useCases.listItems().size })
    }
}
