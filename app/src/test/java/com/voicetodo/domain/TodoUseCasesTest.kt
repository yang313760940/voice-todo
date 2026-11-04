package com.voicetodo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 用例层验收（对齐票 #2 验收标准）：
 * - 确认保存后持久化事项并请求系统闹钟；
 * - 取消确认不创建事项、不调度；
 * - 重开（新用例实例读同一仓库）可见保存内容。
 */
class TodoUseCasesTest {

    private val now = LocalDateTime.of(2026, 11, 4, 9, 0, 0)
    private val repo = InMemoryTodoRepository()
    private val scheduler = RecordedScheduler()
    private val clock = FrozenClock(now)
    private fun useCases() = TodoUseCases(repo, scheduler, clock)

    @Test
    fun `confirming a draft persists the item and schedules a system alarm`() {
        val future = now.plusHours(2)
        val result = useCases().confirmDraft(title = "买牛奶", remindAt = future)

        assertEquals(1, repo.all().size)
        val saved = repo.all().single()
        assertEquals("买牛奶", saved.title)
        assertEquals(future, saved.remindAt)
        // 确实请求了系统调度，且带同一 id 与时刻
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(saved.id, scheduler.scheduled.single().itemId)
        assertEquals(future, scheduler.scheduled.single().at)
        assertTrue(result.scheduled)
    }

    @Test
    fun `cancelling a draft creates no item and schedules nothing`() {
        useCases().cancelDraft()

        assertEquals(0, repo.all().size)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `a saved item is visible after reopening`() {
        val future = now.plusHours(3)
        useCases().confirmDraft(title = "交报告", remindAt = future)

        // 新用例实例 = 重开应用，读同一持久化仓库
        val reopened = TodoUseCases(repo, RecordedScheduler(), clock)
        val visible = reopened.listItems()

        assertEquals(1, visible.size)
        assertEquals("交报告", visible.single().title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `confirming with blank title is rejected`() {
        useCases().confirmDraft(title = "   ", remindAt = now.plusHours(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `confirming with a past remind time is rejected`() {
        useCases().confirmDraft(title = "过期", remindAt = now.minusMinutes(1))
    }

    @Test
    fun `rejected confirm leaves repository empty and nothing scheduled`() {
        try {
            useCases().confirmDraft(title = "", remindAt = now.plusHours(1))
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(0, repo.all().size)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `deleting an item cancels its alarm and removes it`() {
        val uc = useCases()
        val r = uc.confirmDraft(title = "可删", remindAt = now.plusHours(1))
        val id = repo.all().single().id

        uc.deleteItem(id)

        assertNull(repo.findById(id))
        assertTrue(scheduler.cancelled.contains(id))
        assertTrue(r.scheduled)
        assertFalse(repo.all().isNotEmpty())
    }

    @Test
    fun `when scheduling fails the item is still saved but reported as not scheduled`() {
        // 模拟“缺精确闹钟权限”的系统调度失败：不抛异常、不回滚，但如实返回 false，
        // 使上层能提示“提醒未就绪”，绝不误报已安排（AC5）。
        val failingScheduler = RecordedScheduler(scheduleSucceeds = false)
        val uc = TodoUseCases(repo, failingScheduler, clock)

        val result = uc.confirmDraft(title = "无权限", remindAt = now.plusHours(1))

        assertFalse("调度失败应如实返回未安排", result.scheduled)
        assertEquals("事项仍应保存", 1, repo.all().size)
    }
}
