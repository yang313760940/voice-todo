package com.voicetodo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetodo.ai.AiPromptContext
import com.voicetodo.ai.AiSettings
import com.voicetodo.ai.OpenAiCompatibleClient
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证设备上的明文回环例外确实可用，且请求仍只带当前文字与日期上下文。 */
@RunWith(AndroidJUnit4::class)
class OpenAiLocalHttpInstrumentedTest {
    @Test
    fun userConfiguredLoopbackServiceWorksAndReturnsAnEditableDraft() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestBody = AtomicReference<String>()
        val content = JSONObject()
            .put("title", "设备端测试草稿")
            .put("plannedDate", "2026-11-06")
            .put("quadrant", "NOT_IMPORTANT_URGENT")
            .toString()
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", content)),
                ),
            )
            .toString()
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    if (next < 0) break
                    headers.append(next.toChar())
                }
                val length = Regex("(?i)content-length:\\s*(\\d+)")
                    .find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(body, read, length - read)
                    if (count < 0) break
                    read += count
                }
                requestBody.set(String(body, 0, read, Charsets.UTF_8))
                val bytes = response.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(Charsets.ISO_8859_1),
                    )
                    write(bytes)
                    flush()
                }
            }
        }
        try {
            val draft = runBlocking {
                OpenAiCompatibleClient(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                    .organize(
                        "tomorrow buy milk",
                        AiPromptContext(Instant.parse("2026-11-05T01:00:00Z"), ZoneId.of("Asia/Shanghai")),
                        AiSettings(
                            "http://127.0.0.1:${server.localPort}/v1",
                            "mock-model",
                            "test" + "-key",
                        ),
                    )
            }

            assertEquals("设备端测试草稿", draft.title)
            val userText = JSONObject(requestBody.get())
                .getJSONArray("messages")
                .getJSONObject(1)
                .getString("content")
            assertTrue(userText.contains("tomorrow buy milk"))
            assertTrue(userText.contains("Asia/Shanghai"))
        } finally {
            server.close()
            worker.join(1_000)
        }
    }
}
