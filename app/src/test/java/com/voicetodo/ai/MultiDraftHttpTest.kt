package com.voicetodo.ai

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.voicetodo.domain.RecurrenceFrequency
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MultiDraftHttpTest {
    private val context = AiPromptContext(
        Instant.parse("2026-11-05T01:00:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )

    @Test
    fun `one sentence returns three separate drafts including a recurring suggestion`() = runBlocking {
        val content = """{"drafts":[
            {"title":"买菜","plannedDate":"2026-11-06"},
            {"title":"锻炼","recurrence":{
                "frequency":"WEEKLY","weekdays":[1,3,5],"time":"18:30"
            }},
            {"title":"交报告","clarification":"请补充具体日期"}
        ]}"""
        val response = response(content)
        LocalHttpServer(200, response).use { server ->
            val source = "明天买菜，每周一三五六点半锻炼，过几天交报告"
            val drafts = OpenAiCompatibleClient().organizeDrafts(
                source,
                context,
                AiSettings("http://127.0.0.1:${server.port}/v1", "test-model", "test-key"),
            )

            assertEquals(listOf("买菜", "锻炼", "交报告"), drafts.map { it.title })
            assertEquals(3, drafts.map { it.itemId }.distinct().size)
            assertTrue(drafts.all { it.sourceText == source })
            assertTrue(drafts[0].reminders.isEmpty())
            assertEquals(RecurrenceFrequency.Weekly, drafts[1].recurrence!!.frequency)
            assertEquals(
                setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                drafts[1].recurrence!!.weekdays,
            )
            val rule = drafts[1].recurrence!!.toRule(LocalDate.of(2026, 11, 5))
            assertEquals(listOf(LocalTime.of(18, 30)), rule.reminderTimes)
            assertTrue(drafts[2].requiresNoReminderAcknowledgement)
        }
    }

    @Test
    fun `malformed fields in any member reject the whole response`() = runBlocking {
        val invalidRecords = listOf(
            """{"title":[]}""",
            """{"title":"提醒格式错","reminders":{}}""",
            """{"title":"提醒时长错","dueAt":"2026-11-06 18:00",
                "reminders":[{"kind":"RELATIVE","minutesBeforeDue":1.5}]}""",
            """{"title":"重复日期错","recurrence":{
                "frequency":"MONTHLY","monthDay":32,"time":"09:00"
            }}""",
        )
        for (invalid in invalidRecords) {
            val response = response("""{"drafts":[{"title":"有效事项"},$invalid]}""")
            LocalHttpServer(200, response).use { server ->
                val failure = runCatching {
                    OpenAiCompatibleClient().organizeDrafts(
                        "保留原文",
                        context,
                        AiSettings("http://127.0.0.1:${server.port}/v1", "test-model", "test-key"),
                    )
                }.exceptionOrNull()
                assertTrue("无效结构必须整体拒绝：$invalid", failure is AiAssistException)
            }
        }
    }

    private fun response(content: String): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put(
            "message",
            JSONObject().put("content", content),
        )))
        .toString()

}
