package com.voicetodo.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TodoOverdueTest {

    private val createdAt = Instant.parse("2026-11-04T08:00:00Z")

    @Test
    fun `an item without a due time is never overdue`() {
        val item = TodoItem(
            title = "无截止时间",
            createdAt = createdAt,
        )

        assertFalse(item.isOverdue(createdAt.plusSeconds(1)))
    }

    @Test
    fun `an item is not overdue exactly at its due time`() {
        val dueAt = Instant.parse("2026-11-04T10:00:00Z")
        val item = TodoItem(title = "刚好到期", dueAt = dueAt, createdAt = createdAt)

        assertFalse(item.isOverdue(dueAt))
    }

    @Test
    fun `an incomplete item is overdue after its due time`() {
        val dueAt = Instant.parse("2026-11-04T10:00:00Z")
        val item = TodoItem(title = "已过截止", dueAt = dueAt, createdAt = createdAt)

        assertTrue(item.isOverdue(dueAt.plusSeconds(1)))
    }

    @Test
    fun `a completed item is not overdue after its due time`() {
        val dueAt = Instant.parse("2026-11-04T10:00:00Z")
        val item = TodoItem(
            title = "已完成",
            dueAt = dueAt,
            completedAt = dueAt.minusSeconds(60),
            createdAt = createdAt,
        )

        assertFalse(item.isOverdue(dueAt.plusSeconds(1)))
    }
}
