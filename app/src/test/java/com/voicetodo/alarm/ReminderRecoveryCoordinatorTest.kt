package com.voicetodo.alarm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.InMemoryTodoRepository
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.TodoUseCases
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReminderRecoveryCoordinatorTest {
    @Test fun successfulReconcilePersistsZoneAndNextEntryDoesNotRebaseAgain() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences(ReminderRecoveryCoordinator.PREFERENCES, Application.MODE_PRIVATE)
            .edit().clear().commit()
        val repo = InMemoryTodoRepository()
        val scheduler = RecordedScheduler()
        val useCases = TodoUseCases(repo, scheduler,
            FrozenClock(Instant.parse("2026-11-04T00:00:00Z")), ZoneId.of("Asia/Shanghai"))
        var zone = ZoneId.of("Asia/Shanghai")
        val coordinator = ReminderRecoveryCoordinator(app, useCases) { zone }
        coordinator.reconcile()
        val series = useCases.createRecurring(
            "每天九点",
            RecurrenceRule(RecurrenceFrequency.Daily, LocalDate.of(2026, 11, 4),
                reminderTimes = listOf(LocalTime.of(9, 0))),
        ).item
        zone = ZoneId.of("America/Los_Angeles")

        coordinator.reconcile()
        val once = useCases.listIncomplete().filter { it.seriesId == series.id }
            .associate { it.id to it.reminders.map { reminder -> reminder.at } }
        coordinator.reconcile()
        val twice = useCases.listIncomplete().filter { it.seriesId == series.id }
            .associate { it.id to it.reminders.map { reminder -> reminder.at } }

        assertEquals(once, twice)
        assertEquals(zone.id, app.getSharedPreferences(ReminderRecoveryCoordinator.PREFERENCES,
            Application.MODE_PRIVATE).getString(ReminderRecoveryCoordinator.KEY_LAST_ZONE, null))
    }
}
