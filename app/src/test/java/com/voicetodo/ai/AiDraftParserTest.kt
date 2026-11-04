package com.voicetodo.ai

import com.voicetodo.domain.Quadrant
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiDraftParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `parses one draft with plan due multiple reminders and suggested quadrant`() {
        val content = """
            ```json
            {
              "title": "提交季度报告",
              "plannedDate": "2026-11-05",
              "dueAt": "2026-11-06 20:30",
              "reminders": [
                {"kind": "SPECIFIED", "at": "2026-11-05T18:15:00+08:00"},
                {"kind": "RELATIVE", "minutesBeforeDue": 60}
              ],
              "quadrant": "IMPORTANT_URGENT",
              "clarification": null
            }
            ```
        """.trimIndent()

        val draft = AiDraftParser.parse(content, "明天下午交报告", zone)

        assertEquals("提交季度报告", draft.title)
        assertEquals("2026-11-05", draft.plannedDate)
        assertEquals("2026-11-06 20:30", draft.dueAt)
        assertEquals(2, draft.reminders.size)
        assertEquals(AiReminderKind.Specified, draft.reminders[0].kind)
        assertEquals("2026-11-05 18:15", draft.reminders[0].value)
        assertEquals(AiReminderKind.Relative, draft.reminders[1].kind)
        assertEquals("60", draft.reminders[1].value)
        assertEquals(Quadrant.ImportantUrgent, draft.quadrant)
        assertEquals(Quadrant.ImportantUrgent, draft.suggestedQuadrant)
        assertEquals("明天下午交报告", draft.sourceText)
        assertFalse(draft.requiresNoReminderAcknowledgement)

        val edit = draft.toTodoEdit(zone)
        assertEquals("2026-11-05", edit.plannedDate.toString())
        assertEquals(Instant.parse("2026-11-06T12:30:00Z"), edit.dueAt)
        assertEquals(Instant.parse("2026-11-05T10:15:00Z"), edit.reminders!![0].at)
        assertEquals(60L, edit.reminders!![1].beforeDue!!.toMinutes())
    }

    @Test
    fun `ambiguous time with no reminder requires explicit acknowledgement`() {
        val draft = AiDraftParser.parse(
            """{"title":"交报告","clarification":"时间含糊，请补充具体哪天和几点"}""",
            "过几天交报告",
            zone,
        )

        assertTrue(draft.requiresNoReminderAcknowledgement)
        assertNull("模型未给出象限时不虚构建议", draft.suggestedQuadrant)
        assertFalse(draft.copy(noReminderAcknowledged = true).requiresNoReminderAcknowledgement)
        val edit = draft.toTodoEdit(zone)
        assertEquals(null, edit.plannedDate)
        assertEquals(null, edit.dueAt)
        assertTrue(edit.reminders!!.isEmpty())
    }

    @Test
    fun `a planned date alone is allowed and does not invent a reminder`() {
        val draft = AiDraftParser.parse(
            """{"title":"买菜","plannedDate":"2026-11-06","reminders":[]}""",
            "明天买菜",
            zone,
        )

        assertEquals("2026-11-06", draft.plannedDate)
        assertTrue(draft.reminders.isEmpty())
        assertFalse(draft.requiresNoReminderAcknowledgement)
    }

    @Test
    fun `invalid reminder or missing title is rejected instead of executed`() {
        try {
            AiDraftParser.parse("""{"title":"坏提醒","reminders":[{"kind":"UNKNOWN"}]}""", "x", zone)
            fail("未知提醒类型必须拒绝")
        } catch (e: AiAssistException) {
            assertTrue(e.message!!.contains("未知"))
        }
        try {
            AiDraftParser.parse("""{"title":"","plannedDate":"2026-11-06"}""", "x", zone)
            fail("缺少标题必须拒绝")
        } catch (e: AiAssistException) {
            assertTrue(e.message!!.contains("事项名称"))
        }
    }

    @Test
    fun `relative reminder without a due time fails editable validation`() {
        val draft = AiDraftParser.parse(
            """{"title":"提前提醒","reminders":[{"kind":"RELATIVE","minutesBeforeDue":30}]}""",
            "提醒我",
            zone,
        )
        try {
            draft.toTodoEdit(zone)
            fail("没有截止时间时相对提醒无效")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("截止"))
        }
    }
}
