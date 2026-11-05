package com.voicetodo.domain

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 操作层：通知入口的完成 / 延后 / 关闭三种动作。
 * 关注事项、重复周期与提示状态彼此独立，反复点击不重复调度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderActionsTest {
    private val now = Instant.parse("2026-11-05T09:00:00Z")
    private val at = Instant.parse("2026-11-05T10:00:00Z")

    private fun useCases(scheduler: RecordedScheduler = RecordedScheduler(), clock: FrozenClock = FrozenClock(now)) =
        TodoUseCases(InMemoryTodoRepository(), scheduler, clock, java.time.ZoneId.of("UTC"))

    @Test fun completeFromReminderMarksDoneAndCancelsThisInstanceReminder() = runTest {
        val scheduler = RecordedScheduler()
        val useCases = useCases(scheduler)
        val item = useCases.confirmDraft("买菜", at).item

        val result = useCases.completeFromReminder(item.id)

        assertFalse(result.ignored)
        assertEquals(ReminderAction.Complete, result.action)
        assertEquals(now, useCases.findItem(item.id).completedAt)
        assertTrue("完成必须撤销本实例未来提醒", scheduler.cancelled.contains(item.id))
    }

    @Test fun completeFromReminderKeepsNextRecurringOccurrence() = runTest {
        val scheduler = RecordedScheduler()
        val useCases = useCases(scheduler)
        val rule = RecurrenceRule(RecurrenceFrequency.Daily, startDate = now.atZone(java.time.ZoneId.of("UTC")).toLocalDate())
        val series = useCases.createRecurring("每日打卡", rule).item

        val instance = useCases.listIncomplete().first { it.seriesId == series.id }
        useCases.completeFromReminder(instance.id)

        val remaining = useCases.listIncomplete().filter { it.seriesId == series.id }
        assertTrue("完成本次不影响后续重复实例", remaining.isNotEmpty())
        assertTrue(remaining.none { it.id == instance.id && it.completedAt == null })
    }

    @Test fun snoozeReschedulesAlarmWithoutChangingDueOrNextCycle() = runTest {
        val scheduler = RecordedScheduler()
        val useCases = useCases(scheduler)
        val due = Instant.parse("2026-11-05T18:00:00Z")
        val item = useCases.confirmDraft("写报告", at, dueAt = due).item

        val result = useCases.snoozeFromReminder(item.id, SnoozeOption.ThirtyMinutes)

        val until = now.plusSeconds(30 * 60)
        assertEquals(until, result.snoozeUntil)
        val saved = useCases.findItem(item.id)
        assertEquals("延后只改提醒时刻", until, saved.remindAt)
        assertEquals("延后不改截止时间", due, saved.dueAt)
        assertNull(saved.completedAt)
        assertNotNull(saved.dismissedAt)
        assertTrue("必须实际重新登记系统闹钟",
            scheduler.pending.values.any { it.reminderId == item.id && it.at == until })
        // 重新登记的新修订号必须能通过投递校验，否则延后的闹钟会被当成过期丢弃。
        assertTrue("延后后的提醒必须仍可投递",
            saved.isCurrentReminder(item.id, item.id, saved.reminderRevision, until.toEpochMilli()))
    }

    @Test fun snoozeSupportsAllPresetsAndCustomTime() = runTest {
        val presets = mapOf(SnoozeOption.TenMinutes to 10L, SnoozeOption.ThirtyMinutes to 30L,
            SnoozeOption.OneHour to 60L)
        for ((option, minutes) in presets) {
            val scheduler = RecordedScheduler()
            val useCases = useCases(scheduler)
            val item = useCases.confirmDraft("预设延后", at).item
            val result = useCases.snoozeFromReminder(item.id, option)
            assertEquals(now.plusSeconds(minutes * 60), result.snoozeUntil)
        }
        val scheduler = RecordedScheduler()
        val useCases = useCases(scheduler)
        val item = useCases.confirmDraft("自选延后", at).item
        val custom = Instant.parse("2026-11-06T09:30:00Z")
        val result = useCases.snoozeFromReminderAt(item.id, custom)
        assertEquals(custom, result.snoozeUntil)
        assertEquals(custom, useCases.findItem(item.id).remindAt)
    }

    @Test fun snoozeRejectsPastTimeAndKeepsItem() = runTest {
        val useCases = useCases()
        val item = useCases.confirmDraft("过去延后", at).item
        val failure = runCatching { useCases.snoozeFromReminderAt(item.id, now.minusSeconds(60)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(at, useCases.findItem(item.id).remindAt)
        assertNull(useCases.findItem(item.id).dismissedAt)
    }

    @Test fun dismissDoesNotCompleteAndOnlyEndsThisReminder() = runTest {
        val scheduler = RecordedScheduler()
        val useCases = useCases(scheduler)
        val item = useCases.confirmDraft("关闭但不完成", at).item

        val result = useCases.dismissReminder(item.id)

        assertFalse(result.ignored)
        assertEquals(ReminderAction.Dismiss, result.action)
        val saved = useCases.findItem(item.id)
        assertNull("关闭不完成事项", saved.completedAt)
        assertNotNull(saved.dismissedAt)
        assertTrue(scheduler.cancelled.contains(item.id))
    }

    @Test fun repeatedActionsDoNotScheduleTwice() = runTest {
        val scheduler = RecordedScheduler()
        val useCases = useCases(scheduler)
        val item = useCases.confirmDraft("反复点击", at).item

        val first = useCases.snoozeFromReminder(item.id, SnoozeOption.TenMinutes, item.id, 0L)
        val second = useCases.snoozeFromReminder(item.id, SnoozeOption.TenMinutes, item.id, 0L)

        assertFalse(first.ignored)
        assertTrue("重复点击必须被忽略", second.ignored)
        val until = now.plusSeconds(10 * 60)
        assertEquals(1, scheduler.scheduled.count { it.itemId == item.id && it.at == until })
    }

    @Test fun repeatedCompleteAndDismissAreIgnored() = runTest {
        val useCases = useCases()
        val first = useCases.confirmDraft("完成幂等", at).item
        assertFalse(useCases.completeFromReminder(first.id).ignored)
        assertTrue(useCases.completeFromReminder(first.id).ignored)

        val second = useCases.confirmDraft("关闭幂等", at).item
        assertFalse(useCases.dismissReminder(second.id, second.id, 0L).ignored)
        assertTrue(useCases.dismissReminder(second.id, second.id, 0L).ignored)
    }

    @Test fun multipleItemsFiringTogetherAreHandledIndependently() = runTest {
        val scheduler = RecordedScheduler()
        val useCases = useCases(scheduler)
        val a = useCases.confirmDraft("事项A", at).item
        val b = useCases.confirmDraft("事项B", at).item

        useCases.snoozeFromReminder(a.id, SnoozeOption.TenMinutes)
        useCases.dismissReminder(b.id)

        assertEquals(now.plusSeconds(600), useCases.findItem(a.id).remindAt)
        assertNull(useCases.findItem(b.id).completedAt)
        assertNotNull(useCases.findItem(b.id).dismissedAt)
        // A 的延后不影响 B：B 仍保持原提醒时刻，只是本次已被关闭。
        assertEquals(at, useCases.findItem(b.id).remindAt)
    }

    @Test fun persistentRingToggleIsPersistedAndIndependentOfCompletion() = runTest {
        val useCases = useCases()
        val item = useCases.confirmDraft("持续响铃", at).item
        assertFalse(item.persistentRing)
        val updated = useCases.setPersistentRing(item.id, true)
        assertTrue(updated.persistentRing)
        assertNull(updated.completedAt)
        assertFalse(useCases.setPersistentRing(item.id, false).persistentRing)
    }
}
