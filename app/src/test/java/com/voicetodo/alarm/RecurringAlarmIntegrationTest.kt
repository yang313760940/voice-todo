package com.voicetodo.alarm

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.VoiceTodoApp
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.TodoEdit
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecurringAlarmIntegrationTest {
    @Test fun realAlarmAdapterSchedulesTwoConsecutiveInstancesAndKeepsTheNextAfterCompletion() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val alarms = app.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val next = ZonedDateTime.now(ZoneId.systemDefault()).plusHours(1).withSecond(0).withNano(0)
        val rule = RecurrenceRule(
            frequency = RecurrenceFrequency.Daily,
            startDate = next.toLocalDate(),
            reminderTimes = listOf(next.toLocalTime()),
        )
        val series = app.useCases.createRecurring("重复系统闹钟", rule).item
        try {
            val firstTwo = app.useCases.listIncomplete()
                .filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }
            assertEquals(2, firstTwo.size)

            fun registered() = shadowOf(alarms).scheduledAlarms.filter {
                shadowsItemId(it) in firstTwo.map { item -> item.id }.toSet() ||
                    shadowsItemId(it) == series.id
            }
            assertEquals(2, registered().size)
            assertEquals(2, registered().map { it.operation }.distinct().size)

            app.useCases.completeItem(firstTwo[0].id)
            val afterCompletion = app.useCases.listIncomplete()
                .filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }
            assertEquals(2, afterCompletion.size)
            assertTrue(afterCompletion.none { it.id == firstTwo[0].id })
            assertTrue(afterCompletion.any { it.id == firstTwo[1].id })
            val activeAlarmIds = shadowOf(alarms).scheduledAlarms.mapNotNull(::shadowsItemId).toSet()
            assertFalse(firstTwo[0].id in activeAlarmIds)
            assertTrue(firstTwo[1].id in activeAlarmIds)
            assertTrue(afterCompletion.all { it.id in activeAlarmIds })
        } finally {
            app.useCases.stopRecurrence(series.id)
        }
    }

    @Test fun clearingOneOccurrenceReminderCancelsOnlyThatOccurrence() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()
        val alarms = app.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val next = ZonedDateTime.now(ZoneId.systemDefault()).plusHours(1).withSecond(0).withNano(0)
        val series = app.useCases.createRecurring(
            "只取消本次",
            RecurrenceRule(RecurrenceFrequency.Daily, next.toLocalDate(), reminderTimes = listOf(next.toLocalTime())),
        ).item
        try {
            val firstTwo = app.useCases.listIncomplete()
                .filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }
            app.useCases.updateItem(
                firstTwo[0].id,
                TodoEdit(firstTwo[0].title, firstTwo[0].plannedDate, null, null, reminders = emptyList()),
            )

            assertTrue(app.useCases.findItem(firstTwo[0].id).reminders.isEmpty())
            assertNotNull(app.useCases.findItem(series.id).recurrence)
            val activeAlarmIds = shadowOf(alarms).scheduledAlarms.mapNotNull(::shadowsItemId).toSet()
            assertFalse(firstTwo[0].id in activeAlarmIds)
            assertTrue(firstTwo[1].id in activeAlarmIds)
        } finally {
            app.useCases.stopRecurrence(series.id)
        }
    }

    private fun shadowsItemId(alarm: ShadowAlarmManager.ScheduledAlarm): String? =
        shadowOf(alarm.operation).savedIntent.getStringExtra(SystemReminderScheduler.EXTRA_ITEM_ID)
}
