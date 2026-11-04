package com.voicetodo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * 锁屏响铃闭环的宿主侧驱动器（issue #25）：
 * 只负责用真实 [com.voicetodo.alarm.SystemReminderScheduler] 安排一个
 * fireInSec 秒后到点的系统闹钟，然后立即结束。
 * 注意：Android 会在 instrumentation 结束时 force-stop 目标进程；宿主必须随后
 * 正常启动应用以恢复调度，再切后台/退出进程、锁屏。不能把结束 instrumentation
 * 当作普通进程退出。scripts/test-lock-screen-reminder.ps1 负责这些步骤与断言。
 *
 * 用法：
 *   am instrument -w -e class com.voicetodo.AlarmFireScheduleHelperTest#scheduleOnly
 *     -e fireInSec 60 com.voicetodo.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 输出标记（logcat tag AlarmFix）：
 *   SCHEDULED_ITEM=<id> FIRE_AT=<epochMillis>
 */
@RunWith(AndroidJUnit4::class)
class AlarmFireScheduleHelperTest {

    private val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()

    @Test
    fun scheduleOnly() {
        val args = InstrumentationRegistry.getArguments()
        // 宿主手动调用才运行，常规 connectedDebugAndroidTest 不遗留未来闹钟。
        org.junit.Assume.assumeTrue("host-only helper", args.containsKey("fireInSec"))
        val fireInSec = args.getString("fireInSec")!!.toLong()
        val at = Instant.now().plusSeconds(fireInSec)
        val saved = runBlocking { app.useCases.confirmDraft("锁屏响铃复现", at) }
        assertTrue("应已安排系统闹钟", saved.outcome == com.voicetodo.domain.ReminderOutcome.Scheduled)
        val atMillis = at.toEpochMilli()
        android.util.Log.i("AlarmFix", "SCHEDULED_ITEM=${saved.item.id} FIRE_AT=$atMillis")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("scheduledItem", saved.item.id)
            putString("fireAt", atMillis.toString())
        })
    }

    /** 清理：按 id 删除复现用事项。am instrument -e class ...#cleanup -e itemId <id> */
    @Test
    fun cleanup() {
        val itemId = InstrumentationRegistry.getArguments().getString("itemId") ?: return
        runBlocking { app.useCases.deleteItem(itemId) }
        android.util.Log.i("AlarmFix", "CLEANED_ITEM=$itemId")
    }
}
