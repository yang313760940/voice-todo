package com.voicetodo.ai

import android.app.Application
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OpenAiCompatibleClientTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val context = AiPromptContext(Instant.parse("2026-11-05T01:00:00Z"), zone)
    private val key = "test" + "-key"
    private fun settingsFor(port: Int) =
        AiSettings("http://127.0.0.1:$port/v1", "test-model", key)

    @Test
    fun `sends only the current text and date context to the configured endpoint`() {
        val content = JSONObject()
            .put("title", "提交季度报告")
            .put("plannedDate", "2026-11-05")
            .put("dueAt", "2026-11-06 20:30")
            .put(
                "reminders",
                JSONArray().put(JSONObject().put("kind", "SPECIFIED").put("at", "2026-11-05 18:15")),
            )
            .put("quadrant", "IMPORTANT_NOT_URGENT")
            .toString()
        LocalHttpServer(200, chatResponse(content)).use { server ->
            val draft = runBlocking {
                OpenAiCompatibleClient().organize("明天下午交季度报告", context, settingsFor(server.port))
            }

            assertEquals("提交季度报告", draft.title)
            assertEquals("2026-11-06 20:30", draft.dueAt)
            val request = server.requests.single()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer $key", request.headers["authorization"])
            val body = JSONObject(request.body)
            assertEquals("test-model", body.getString("model"))
            val messages = body.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            val userText = messages.getJSONObject(1).getString("content")
            assertTrue(userText.contains("明天下午交季度报告"))
            assertTrue(userText.contains("今天：2026-11-05"))
            assertTrue(userText.contains("时区：Asia/Shanghai"))
            assertTrue(userText.contains("现在：2026-11-05 09:00"))
            assertFalse("请求体不能包含密钥", request.body.contains(key))
            assertFalse("请求体不能包含历史事项字段", request.body.contains("items"))
        }
    }

    @Test
    fun `sends existing list names and parses note plus list name`() {
        val content = JSONObject()
            .put("title", "复诊")
            .put("note", "下午去")
            .put("listName", "健康")
            .put("plannedDate", "2026-11-06")
            .toString()
        LocalHttpServer(200, chatResponse(content)).use { server ->
            val requestContext = context.copy(listNames = listOf("工作", "健康"))
            val draft = runBlocking {
                OpenAiCompatibleClient().organize("明天下午复诊", requestContext, settingsFor(server.port))
            }

            assertEquals("下午去", draft.note)
            assertEquals("健康", draft.listName)
            val userText = JSONObject(server.requests.single().body)
                .getJSONArray("messages")
                .getJSONObject(1)
                .getString("content")
            assertTrue(userText.contains("已有清单：工作、健康"))
            assertFalse("不发送历史事项", userText.contains("历史事项"))
            val systemText = JSONObject(server.requests.single().body)
                .getJSONArray("messages")
                .getJSONObject(0)
                .getString("content")
            assertTrue(systemText.contains("只采用最后一次说法"))
        }
    }

    @Test
    fun `bad credentials fail once with an actionable message and no retry`() {
        LocalHttpServer(401, """{"error":"invalid key"}""").use { server ->
            try {
                runBlocking {
                    OpenAiCompatibleClient().organize("买牛奶", context, settingsFor(server.port))
                }
                fail("401 必须失败")
            } catch (e: AiAssistException) {
                assertTrue(e.message!!.contains("拒绝"))
                assertTrue(e.message!!.contains("文字已保留"))
            }
            assertEquals("客户端不应自动重试", 1, server.requests.size)
        }
    }

    @Test
    fun `timeout is reported while preserving the action for the user`() {
        LocalHttpServer(200, chatResponse("""{"title":"超时测试"}"""), delayMillis = 400).use { server ->
            try {
                runBlocking {
                    OpenAiCompatibleClient(connectTimeoutMillis = 500, readTimeoutMillis = 100)
                        .organize("买牛奶", context, settingsFor(server.port))
                }
                fail("超时必须失败")
            } catch (e: AiAssistException) {
                assertTrue(e.message!!.contains("超时"))
                assertTrue(e.message!!.contains("文字已保留"))
            }
        }
    }

    @Test
    fun `malformed model response and http errors do not execute anything`() {
        LocalHttpServer(200, """{"choices":[]}""").use { server ->
            try {
                runBlocking {
                    OpenAiCompatibleClient().organize("买牛奶", context, settingsFor(server.port))
                }
                fail("无内容必须失败")
            } catch (e: AiAssistException) {
                assertTrue(e.message!!.contains("结构"))
            }
        }
        LocalHttpServer(500, """{"error":"server"}""").use { server ->
            try {
                runBlocking {
                    OpenAiCompatibleClient().organize("买牛奶", context, settingsFor(server.port))
                }
                fail("500 必须失败")
            } catch (e: AiAssistException) {
                assertTrue(e.message!!.contains("HTTP 500"))
            }
        }
    }

    @Test
    fun `offline connection failure keeps text and does not retry`() {
        val port = ServerSocket(0).use { it.localPort }
        try {
            runBlocking {
                OpenAiCompatibleClient(
                    connectTimeoutMillis = 300,
                    readTimeoutMillis = 300,
                ).organize("买牛奶", context, settingsFor(port))
            }
            fail("连接失败必须报错")
        } catch (e: AiAssistException) {
            assertTrue(e.message!!.contains("无法连接"))
            assertTrue(e.message!!.contains("文字已保留"))
        }
    }

    private fun chatResponse(content: String): String = JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put("content", content),
                ),
            ),
        )
        .toString()
}
