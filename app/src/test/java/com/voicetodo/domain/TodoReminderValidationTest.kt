package com.voicetodo.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TodoReminderValidationTest {

    private val remindAt = Instant.parse("2026-11-04T10:00:00Z")
    private val item = TodoItem(
        id = "item-1",
        title = "校验提醒",
        remindAt = remindAt,
        createdAt = Instant.parse("2026-11-04T08:00:00Z"),
        reminderRevision = 3,
    )

    @Test
    fun `matching incomplete reminder is current`() {
        assertTrue(item.isCurrentReminder("item-1", 3, remindAt.toEpochMilli()))
    }

    @Test
    fun `different revision is not current`() {
        assertFalse(item.isCurrentReminder("item-1", 2, remindAt.toEpochMilli()))
    }

    @Test
    fun `different delivery time is not current`() {
        assertFalse(item.isCurrentReminder("item-1", 3, remindAt.toEpochMilli() + 1))
    }

    @Test
    fun `completed reminder is not current`() {
        val completed = item.copy(completedAt = Instant.parse("2026-11-04T09:30:00Z"))

        assertFalse(completed.isCurrentReminder("item-1", 3, remindAt.toEpochMilli()))
    }

    @Test
    fun `different item id is not current`() {
        assertFalse(item.isCurrentReminder("item-2", 3, remindAt.toEpochMilli()))
    }
}
