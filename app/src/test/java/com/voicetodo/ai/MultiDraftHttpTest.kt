package com.voicetodo.ai

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MultiDraftHttpTest {
    @Test fun `malformed fields in any member reject the whole response`() = runBlocking {
        val invalidRecords = listOf(
            """{"title":[]} """,
            """{"title":"提醒格式错","reminders":{}}""",
            """{"title":"提醒时长错","dueAt":"2026-11-06 18:00","reminders":[{"kind":"RELATIVE","minutesBeforeDue":1.5}]}""",
            """{"title":"重复日期错","recurrence":{"frequency":"MONTHLY","monthDay":32}}""",
        )
        for (invalid in invalidRecords) {
            val content = """{"drafts":[{"title":"有效事项"},$invalid]}"""
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", content)))).toString()
            LocalHttpServer(200, response).use { server ->
                val failure = runCatching {
                    OpenAiCompatibleClient().organizeDrafts("保留原文",
                        AiPromptContext(Instant.parse("2026-11-05T01:00:00Z"), ZoneId.of("Asia/Shanghai")),
                        AiSettings("http://127.0.0.1:${server.port}/v1", "test-model", "test-key"))
                }.exceptionOrNull()
                assertTrue("无效结构必须整体拒绝：$invalid", failure is AiAssistException)
            }
        }
    }

    @Test fun `one sentence returns three separate drafts including a recurring suggestion`() = runBlocking {
        val content = """{"drafts":[
            {"title":"买菜","plannedDate":"2026-11-06"},
            {"title":"锻炼","recurrence":{"frequency":"WEEKLY","weekdays":[1,3,5]}},
            {"title":"交报告","clarification":"请补充具体日期"}
        ]}"""
        val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("content", content)))).toString()
        LocalHttpServer(200, response).use { server ->
            val source = "明天买菜，每周一三五锻炼，过几天交报告"
            val drafts = OpenAiCompatibleClient().organizeDrafts(source,
                AiPromptContext(Instant.parse("2026-11-05T01:00:00Z"), ZoneId.of("Asia/Shanghai")),
                AiSettings("http://127.0.0.1:${server.port}/v1", "test-model", "test-key"))
            assertEquals(listOf("买菜", "锻炼", "交报告"), drafts.map { it.title })
            assertEquals(3, drafts.map { it.itemId }.distinct().size)
            assertTrue(drafts.all { it.sourceText == source })
            assertTrue(drafts[0].reminders.isEmpty())
            assertEquals(setOf(1, 3, 5), drafts[1].recurrence!!.weekdays)
            assertTrue(drafts[2].requiresNoReminderAcknowledgement)
        }
    }
}
