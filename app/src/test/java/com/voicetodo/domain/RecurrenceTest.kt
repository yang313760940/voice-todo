package com.voicetodo.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecurrenceTest {
    @get:Rule val temp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-04T00:00:00Z") // 08:00 in Shanghai
    private val date = LocalDate.of(2026, 11, 4)
    private val nine = LocalTime.of(9, 0)

    private suspend fun withUseCases(
        clock: FrozenClock = FrozenClock(now),
        block: suspend (TodoUseCases, RecordedScheduler, FrozenClock) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).build()
        try {
            val scheduler = RecordedScheduler()
            val useCases = TodoUseCases(
                RoomTodoRepository(db, FileTodoRepository(temp.newFolder(), zone)),
                scheduler,
                clock,
                zone,
            )
            block(useCases, scheduler, clock)
        } finally {
            db.close()
        }
    }

    @Test fun dailyWeeklyAndMonthlyRulesSurviveReopenWithStableOccurrenceIdentities() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = temp.newFile("recurrence.db")
        val legacy = FileTodoRepository(temp.newFolder(), zone)
        fun open() = Room.databaseBuilder(context, TodoDatabase::class.java, file.absolutePath).build()

        val rules = listOf(
            RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            RecurrenceRule(
                RecurrenceFrequency.Weekly, date,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                reminderTimes = listOf(nine),
            ),
            RecurrenceRule(
                RecurrenceFrequency.Monthly, date, dayOfMonth = 31,
                reminderTimes = listOf(nine),
            ),
        )

        var db = open()
        val seriesIds = try {
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy), RecordedScheduler(), FrozenClock(now), zone)
            rules.mapIndexed { index, rule ->
                useCases.createRecurring("规则 ${index + 1}", rule).item.id
            }
        } finally {
            db.close()
        }

        db = open()
        try {
            val useCases = TodoUseCases(RoomTodoRepository(db, legacy), RecordedScheduler(), FrozenClock(now), zone)
            seriesIds.forEachIndexed { index, seriesId ->
                assertEquals(rules[index], useCases.findItem(seriesId).recurrence)
                val occurrences = useCases.listIncomplete()
                    .filter { it.seriesId == seriesId }
                    .sortedBy { it.occurrenceDate }
                assertEquals("每条规则都应有接下来两次实例", 2, occurrences.size)
                assertEquals(
                    occurrences.map { "${seriesId}@${it.occurrenceDate}" },
                    occurrences.map { it.id },
                )
                assertTrue(occurrences.all { it.reminders.size == 1 })
                assertEquals(
                    occurrences.map { it.occurrenceDate }.distinct().size,
                    occurrences.size,
                )
                val repeated = useCases.listIncomplete()
                    .filter { it.seriesId == seriesId }
                    .sortedBy { it.occurrenceDate }
                assertEquals("重复读取不能产生重复实例", occurrences.map { it.id }, repeated.map { it.id })
            }
        } finally {
            db.close()
        }
    }

    @Test fun monthlyThirtyFirstUsesLastDayAndRecoversAcrossLeapYearAndMonths() {
        val rule = RecurrenceRule(
            RecurrenceFrequency.Monthly,
            LocalDate.of(2026, 1, 31),
            dayOfMonth = 31,
            reminderTimes = listOf(nine),
        )
        val dates = generateSequence(rule.firstOccurrenceOnOrAfter(rule.startDate)) {
            rule.nextOccurrenceAfter(it)
        }.take(5).toList()
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31),
            ),
            dates,
        )
        assertEquals(
            LocalDate.of(2028, 2, 29),
            rule.firstOccurrenceOnOrAfter(LocalDate.of(2028, 2, 1)),
        )
        assertEquals(
            LocalDate.of(2028, 3, 31),
            rule.nextOccurrenceAfter(LocalDate.of(2028, 2, 29)),
        )
    }

    @Test fun weekdayRuleContainsMondayThroughFridayOnly() {
        val workdays = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        val rule = RecurrenceRule(
            RecurrenceFrequency.Weekly,
            LocalDate.of(2026, 11, 7),
            weekdays = workdays,
            reminderTimes = listOf(nine),
        )
        val dates = generateSequence(rule.firstOccurrenceOnOrAfter(rule.startDate)) {
            rule.nextOccurrenceAfter(it)
        }.take(10).toList()
        assertEquals(LocalDate.of(2026, 11, 9), dates.first())
        assertTrue(dates.all { it.dayOfWeek in workdays })
        assertFalse(dates.any { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test fun completingOrMissingOneOccurrenceKeepsTheSeriesAndSchedulesTheNextInstance() = runTest {
        withUseCases { useCases, scheduler, clock ->
            val series = useCases.createRecurring(
                "每日复盘",
                RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val firstTwo = useCases.listIncomplete().sortedBy { it.occurrenceDate }
            assertEquals(2, firstTwo.size)
            val first = firstTwo[0]
            val second = firstTwo[1]
            assertNotNull(scheduler.pending[first.id to first.reminders.single().id])
            assertNotNull(scheduler.pending[second.id to second.reminders.single().id])

            useCases.completeItem(first.id)
            assertNotNull(useCases.findItem(series.id).recurrence)
            assertNotNull(useCases.findItem(first.id).completedAt)
            assertFalse(scheduler.pending.keys.any { it.first == first.id })
            assertNotNull(scheduler.pending[second.id to second.reminders.single().id])

            clock.instant = now.plusSeconds(3 * 24 * 3600)
            useCases.reconcileReminders()
            val remaining = useCases.listIncomplete().filter { it.seriesId == series.id }
            assertTrue("漏做几天后仍有未来实例", remaining.any { it.occurrenceDate!! >= LocalDate.of(2026, 11, 7) })
            assertNotNull(useCases.findItem(series.id).recurrence)
        }
    }

    @Test fun clearingOneOccurrenceReminderDoesNotStopTheSeriesOrItsNextReminder() = runTest {
        withUseCases { useCases, scheduler, _ ->
            val series = useCases.createRecurring(
                "每日喝水",
                RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val firstTwo = useCases.listIncomplete().sortedBy { it.occurrenceDate }
            val first = firstTwo[0]
            val second = firstTwo[1]

            useCases.updateItem(
                first.id,
                TodoEdit(first.title, first.plannedDate, null, null, reminders = emptyList()),
            )

            val cleared = useCases.findItem(first.id)
            assertTrue(cleared.reminders.isEmpty())
            assertNull(cleared.remindAt)
            assertFalse(scheduler.pending.keys.any { it.first == first.id })
            assertNotNull(scheduler.pending[second.id to second.reminders.single().id])
            assertNotNull(useCases.findItem(series.id).recurrence)
            assertEquals(2, useCases.listIncomplete().count { it.seriesId == series.id })
        }
    }

    @Test fun anAlarmFiringWhileTheAppStaysClosedStillBooksTheFollowingInstance() = runTest {
        withUseCases { useCases, scheduler, clock ->
            val series = useCases.createRecurring(
                "长期不打开也要继续",
                RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val first = useCases.listIncomplete()
                .filter { it.seriesId == series.id }
                .minBy { it.occurrenceDate!! }
            clock.instant = first.reminders.single().at.plusSeconds(1)

            useCases.ensureNextOccurrence(first.id)

            val thirdDate = first.occurrenceDate!!.plusDays(2)
            val third = useCases.listIncomplete().single {
                it.seriesId == series.id && it.occurrenceDate == thirdDate
            }
            assertNotNull(scheduler.pending[third.id to third.reminders.single().id])
        }
    }

    @Test fun aLaterReminderTimeStillCreatesTodaysInstanceWhenTheEarlierTimeHasPassed() = runTest {
        withUseCases(clock = FrozenClock(Instant.parse("2026-11-04T02:00:00Z"))) { useCases, _, _ ->
            val series = useCases.createRecurring(
                "早晚两次",
                RecurrenceRule(
                    RecurrenceFrequency.Daily, date,
                    reminderTimes = listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                ),
            ).item
            val today = useCases.listIncomplete().single {
                it.seriesId == series.id && it.occurrenceDate == date
            }
            assertEquals(2, today.reminders.size)
            assertEquals(Instant.parse("2026-11-04T10:00:00Z"), today.remindAt)
        }
    }

    @Test fun dstGapMovesToFirstValidInstantAndOverlapSchedulesOnlyOnce() {
        val newYork = ZoneId.of("America/New_York")
        val spring = RecurrenceRule(
            RecurrenceFrequency.Daily,
            LocalDate.of(2026, 3, 7),
            reminderTimes = listOf(LocalTime.of(2, 30)),
        )
        assertEquals(
            Instant.parse("2026-03-07T07:30:00Z"),
            spring.instantOn(LocalDate.of(2026, 3, 7), LocalTime.of(2, 30), newYork),
        )
        assertEquals(
            "02:30 不存在时使用跳时后的首个有效时刻",
            Instant.parse("2026-03-08T07:00:00Z"),
            spring.instantOn(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), newYork),
        )
        assertEquals(
            Instant.parse("2026-03-09T06:30:00Z"),
            spring.instantOn(LocalDate.of(2026, 3, 9), LocalTime.of(2, 30), newYork),
        )

        val fall = RecurrenceRule(
            RecurrenceFrequency.Daily,
            LocalDate.of(2026, 11, 1),
            reminderTimes = listOf(LocalTime.of(1, 30)),
        )
        assertEquals(
            "01:30 重复时只取较早偏移",
            Instant.parse("2026-11-01T05:30:00Z"),
            fall.instantOn(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), newYork),
        )
        val dates = generateSequence(fall.firstOccurrenceOnOrAfter(fall.startDate)) {
            fall.nextOccurrenceAfter(it)
        }.take(3).toList()
        assertEquals(dates.distinct(), dates)
        assertEquals(3, dates.map { fall.instantOn(it, LocalTime.of(1, 30), newYork) }.distinct().size)
    }

    @Test fun oneTimeReminderKeepsItsExactInstantWhileRecurringReminderUsesLocalWallClock() = runTest {
        withUseCases { useCases, _, _ ->
            val oneShotAt = Instant.parse("2026-11-04T04:30:00Z")
            val oneShot = useCases.confirmDraft("一次性", oneShotAt).item
            val series = useCases.createRecurring(
                "每日",
                RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(LocalTime.of(12, 30))),
            ).item
            val occurrences = useCases.listIncomplete()
                .filter { it.seriesId == series.id }
                .sortedBy { it.occurrenceDate }

            assertEquals(oneShotAt, useCases.findItem(oneShot.id).remindAt)
            assertEquals(
                listOf(
                    Instant.parse("2026-11-04T04:30:00Z"),
                    Instant.parse("2026-11-05T04:30:00Z"),
                ),
                occurrences.map { it.remindAt },
            )
        }
    }

    @Test fun stoppingARecurringSeriesRemovesFutureInstancesAndKeepsCompletedHistory() = runTest {
        withUseCases { useCases, scheduler, _ ->
            val series = useCases.createRecurring(
                "晨跑",
                RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val occurrences = useCases.listIncomplete().sortedBy { it.occurrenceDate }
            useCases.completeItem(occurrences[0].id)

            useCases.stopRecurrence(occurrences[0].id)

            assertTrue(useCases.listItems().none { it.id == series.id })
            assertNotNull(useCases.findItem(occurrences[0].id).completedAt)
            assertTrue(useCases.listIncomplete().none { it.seriesId == series.id })
            assertTrue(scheduler.pending.keys.none { key -> key.first == series.id || key.first == occurrences[1].id })
        }
    }

    @Test fun stoppingASeriesKeepsPastMissedInstancesAsHistory() = runTest {
        withUseCases { useCases, _, clock ->
            val series = useCases.createRecurring(
                "保留漏做记录",
                RecurrenceRule(RecurrenceFrequency.Daily, date, reminderTimes = listOf(nine)),
            ).item
            val missedDate = useCases.listIncomplete().filter { it.seriesId == series.id }
                .minOf { it.occurrenceDate!! }
            clock.instant = now.plusSeconds(3 * 24 * 3600)
            useCases.reconcileReminders()

            useCases.stopRecurrence(series.id)

            assertNotNull(useCases.listIncomplete().singleOrNull {
                it.seriesId == series.id && it.occurrenceDate == missedDate
            })
            assertTrue(useCases.listIncomplete().none {
                it.seriesId == series.id && it.occurrenceDate!! >= LocalDate.of(2026, 11, 7)
            })
        }
    }
}
