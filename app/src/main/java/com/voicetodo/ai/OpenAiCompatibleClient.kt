package com.voicetodo.ai

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 最小 OpenAI 兼容聊天接口适配器。
 *
 * 只发送当前文字、当前日期时间和时区；不读取事项列表、录音或后台积压。
 * 不自动重试，也不跟随重定向，避免把密钥带到另一个主机。
 */
class OpenAiCompatibleClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 45_000,
) : AiAssistClient {

    override suspend fun organize(
        text: String,
        context: AiPromptContext,
        settings: AiSettings,
    ): AiDraft = withContext(Dispatchers.IO) {
        val clean = settings.validate()
        val body = requestBody(clean, text, context).toString().toByteArray(Charsets.UTF_8)
        val connection = try {
            (clean.endpoint().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${clean.apiKey}")
            }
        } catch (e: Exception) {
            throw AiAssistException("AI 服务地址无效；文字已保留，可手动创建", e)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                code == 401 || code == 403 ->
                    throw AiAssistException("AI 服务拒绝了密钥（HTTP $code）；文字已保留，可检查高级设置")
                code !in 200..299 ->
                    throw AiAssistException("AI 服务返回错误（HTTP $code）；文字已保留，可手动创建")
            }
            val content = try {
                JSONObject(response)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (e: JSONException) {
                throw AiAssistException("AI 返回结构不符合 OpenAI 兼容格式；文字已保留，可手动创建", e)
            }
            AiDraftParser.parse(content, text, context.zoneId)
        } catch (e: AiAssistException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiAssistException("连接 AI 服务超时；文字已保留，可重试或手动创建", e)
        } catch (e: UnknownHostException) {
            throw AiAssistException("无法连接 AI 服务（断网或地址错误）；文字已保留，可手动创建", e)
        } catch (e: ConnectException) {
            throw AiAssistException("无法连接 AI 服务（断网或服务未启动）；文字已保留，可手动创建", e)
        } catch (e: IOException) {
            throw AiAssistException("连接 AI 服务失败；文字已保留，可重试或手动创建", e)
        } catch (e: Exception) {
            throw AiAssistException("AI 整理失败；文字已保留，可手动创建", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBody(settings: AiSettings, text: String, context: AiPromptContext): JSONObject {
        val localNow = LocalDateTime.ofInstant(context.now, context.zoneId)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val userContent = buildString {
            appendLine("当前文字：")
            appendLine(text)
            appendLine()
            appendLine("现在：$localNow")
            appendLine("今天：${localNow.take(10)}")
            append("时区：${context.zoneId.id}")
        }
        return JSONObject()
            .put("model", settings.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", userContent)),
            )
    }

    private companion object {
        val SYSTEM_PROMPT = """
            你是待办事项整理器。只输出一个 JSON 对象，不要 Markdown、不要解释。
            字段：
            - title：字符串，必填，简洁的事项名称。
            - plannedDate："yyyy-MM-dd" 或 null。用户计划哪天处理；它本身不产生提醒。
            - dueAt："yyyy-MM-dd HH:mm" 或 null。最晚完成时刻。
            - reminders：数组，可为空。元素只能是：
              {"kind":"SPECIFIED","at":"yyyy-MM-dd HH:mm"}
              或 {"kind":"RELATIVE","minutesBeforeDue":正整数}。
            - quadrant："IMPORTANT_URGENT"、"IMPORTANT_NOT_URGENT"、"NOT_IMPORTANT_URGENT"、
              "NOT_IMPORTANT_NOT_URGENT" 之一，只是建议。
            - clarification：字符串或 null。时间含糊、缺少关键信息时必须说明，不要猜测。
            规则：
            1. 只整理为一个事项；如果文字明显包含多件事，在 clarification 说明本版只生成一条。
            2. 不要凭空补造日期、截止时间或提醒；含糊时间必须放进 clarification。
            3. “明天买菜”只给 plannedDate，不设置提醒。
            4. 只有用户明确说“提醒我”或给出明确提醒时刻时才生成 reminders。
            5. 所有时间都按用户给出的时区表达；相对提醒必须有 dueAt。
        """.trimIndent()
    }
}
