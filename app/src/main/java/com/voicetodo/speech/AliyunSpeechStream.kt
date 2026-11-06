package com.voicetodo.speech

import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

interface AliyunSpeechStreamFactory {
    fun open(settings: AliyunSpeechSettings): SpeechStream
}

class OkHttpAliyunSpeechStreamFactory(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = AliyunSpeechSettings.DEFAULT_ENDPOINT,
) : AliyunSpeechStreamFactory {
    override fun open(settings: AliyunSpeechSettings): SpeechStream =
        AliyunSpeechStream(settings, client, endpoint)

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 阿里云百炼 qwen3-asr-flash-realtime 适配器。
 *
 * 使用与文本整理相同的百炼 API Key；音频只经内存队列以 base64 分片上传，
 * 不落盘、不自动重试。结束录音时先 commit，再发送 session.finish，
 * 以最终 completed 事件的 transcript 为准。
 */
class AliyunSpeechStream(
    private val settings: AliyunSpeechSettings,
    private val client: OkHttpClient,
    private val endpoint: String = AliyunSpeechSettings.DEFAULT_ENDPOINT,
) : SpeechStream {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
    override val events: Flow<SpeechEvent> = channel.receiveAsFlow()

    private val lock = Any()
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var webSocket: WebSocket? = null
    private var opened = false
    private var sessionReady = false
    private var finishRequested = false
    private var finishSent = false
    private var cancelled = false
    private var terminal = false
    private var completed = false
    private var finalText: String = ""
    private var partialText: String = ""
    private var deltaText: String = ""
    private var eventCounter = 0L
    private var logId: String? = null
    private var language = "zh-CN"

    override fun start(language: String) {
        this.language = language
        val clean = try {
            settings.validate()
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "阿里云百炼语音配置无效", null)
            return
        }
        val request = try {
            Request.Builder()
                .url(endpointWithModel(clean.model))
                .header("Authorization", "Bearer ${clean.apiKey}")
                .header("OpenAI-Beta", "realtime=v1")
                .header("User-Agent", "voice-todo")
                .build()
        } catch (e: Exception) {
            fail("阿里云百炼实时识别地址无效", null)
            return
        }
        webSocket = client.newWebSocket(request, listener)
    }

    override fun pushPcm(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        val copy = pcm.copyOf(length)
        synchronized(lock) {
            if (cancelled || terminal || finishRequested) return
            if (opened && sessionReady) {
                sendAudio(copy)
            } else {
                pendingAudio.addLast(copy)
            }
        }
    }

    override fun finish() {
        synchronized(lock) {
            if (cancelled || terminal || finishRequested) return
            finishRequested = true
            if (opened && sessionReady) sendCommitAndFinish()
        }
    }

    override fun cancel() {
        synchronized(lock) {
            if (cancelled || terminal) return
            cancelled = true
            pendingAudio.clear()
        }
        webSocket?.cancel()
        terminal = true
        channel.close()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            logId = responseHeaderId(response) ?: logId
            synchronized(lock) {
                if (cancelled || terminal) return
                opened = true
                channel.trySend(SpeechEvent.Connected(logId))
                sendJson(sessionUpdateEvent())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = try {
                JSONObject(text)
            } catch (e: Exception) {
                fail("阿里云百炼响应无法解析：${e.message ?: "格式错误"}", logId)
                return
            }
            when (event.optString("type")) {
                "session.updated" -> {
                    synchronized(lock) {
                        if (cancelled || terminal) return
                        sessionReady = true
                        while (pendingAudio.isNotEmpty()) {
                            sendAudio(pendingAudio.removeFirst())
                        }
                        if (finishRequested) sendCommitAndFinish()
                    }
                }
                "conversation.item.input_audio_transcription.text" -> {
                    val textPart = event.optString("text")
                    val stash = event.optString("stash")
                    val preview = textPart + stash
                    if (preview.isNotBlank()) {
                        partialText = preview
                        channel.trySend(SpeechEvent.Partial(preview))
                    }
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = event.optString("delta")
                    if (delta.isNotBlank()) {
                        deltaText += delta
                        partialText = deltaText
                        channel.trySend(SpeechEvent.Partial(deltaText))
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    completed = true
                    finalText = if (event.isNull("transcript")) {
                        ""
                    } else {
                        event.optString("transcript")
                    }
                    if (finalText.isNotBlank()) partialText = finalText
                }
                "conversation.item.input_audio_transcription.failed" -> {
                    val error = event.optJSONObject("error")
                    fail(
                        "阿里云百炼识别失败：" + (
                            error?.optString("message").orEmpty().ifBlank { "服务端未返回原因" }
                            ),
                        logId,
                    )
                }
                "session.finished" -> finishWithResult()
                "error" -> {
                    val error = event.optJSONObject("error")
                    val code = error?.opt("code")?.toString()
                        ?: event.opt("code")?.toString()
                    val detail = error?.optString("message").orEmpty()
                        .ifBlank { event.optString("message") }
                    fail(
                        "阿里云百炼识别失败" +
                            (code?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "") +
                            "：" + detail.ifBlank { "服务端未返回原因" },
                        logId,
                    )
                }
                else -> Unit
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            fail("阿里云百炼返回了非预期的二进制消息", logId)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!terminal && !cancelled) {
                fail("阿里云百炼连接已中断，已识别的文字可能不完整", logId)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (cancelled || terminal) return
            val code = response?.code
            val message = when {
                code == 401 || code == 403 ->
                    "阿里云百炼鉴权失败（HTTP $code）：请检查 API Key 是否有效、是否与地域匹配；不会自动重试"
                code == 404 ->
                    "阿里云百炼接口或模型不可用（HTTP 404）：请确认实时语音识别模型已开通"
                code == 429 ->
                    "阿里云百炼请求过于频繁（HTTP 429）；不会自动重试"
                code != null && code >= 500 ->
                    "阿里云百炼服务异常（HTTP $code）"
                code != null -> "阿里云百炼连接失败（HTTP $code）"
                else -> "阿里云百炼连接失败：${t.message ?: "网络错误"}"
            }
            fail("$message；已识别的文字可能不完整", responseHeaderId(response) ?: logId)
        }
    }

    private fun finishWithResult() {
        synchronized(lock) {
            if (terminal || cancelled) return
            terminal = true
            val final = when {
                completed -> finalText.ifBlank { partialText }
                partialText.isNotBlank() -> {
                    channel.trySend(
                        SpeechEvent.Failure(
                            "阿里云百炼未返回最终结果，已识别的文字可能不完整",
                            logId,
                        ),
                    )
                    null
                }
                else -> ""
            }
            if (final != null) channel.trySend(SpeechEvent.Final(final))
            channel.close()
        }
        webSocket?.close(1000, "done")
    }

    private fun sessionUpdateEvent(): String {
        val session = JSONObject()
            .put("modalities", JSONArray().put("text"))
            .put("input_audio_format", "pcm")
            .put("sample_rate", 16000)
            .put(
                "input_audio_transcription",
                JSONObject().put("language", languageCode(language)),
            )
            .put("turn_detection", JSONObject.NULL)
        return JSONObject()
            .put("event_id", nextEventId())
            .put("type", "session.update")
            .put("session", session)
            .toString()
    }

    private fun sendAudio(pcm: ByteArray) {
        val event = JSONObject()
            .put("event_id", nextEventId())
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.getEncoder().encodeToString(pcm))
        sendJson(event.toString())
    }

    private fun sendCommitAndFinish() {
        if (finishRequested && sessionReady && !finishSent) {
            finishSent = true
            sendJson(
                JSONObject()
                    .put("event_id", nextEventId())
                    .put("type", "input_audio_buffer.commit")
                    .toString(),
            )
            sendJson(JSONObject().put("type", "session.finish").toString())
        }
    }

    private fun sendJson(json: String) {
        webSocket?.send(json)
    }

    private fun nextEventId(): String {
        eventCounter += 1
        return "event_$eventCounter"
    }

    private fun endpointWithModel(model: String): String {
        val separator = if (endpoint.contains('?')) '&' else '?'
        val encoded = URLEncoder.encode(model, "UTF-8")
        return "$endpoint$separator" + "model=$encoded"
    }

    private fun languageCode(language: String): String = when (language.lowercase()) {
        "zh", "zh-cn" -> "zh"
        "yue", "yue-cn" -> "yue"
        "en", "en-us" -> "en"
        else -> language.substringBefore('-').lowercase()
    }

    private fun responseHeaderId(response: Response?): String? {
        val headers = response?.headers ?: return null
        return listOf(
            "X-DashScope-Request-Id",
            "X-Request-Id",
            "X-DashScope-Task-Id",
            "X-DashScope-Logid",
            "X-Tt-Logid",
        ).firstNotNullOfOrNull { name ->
            headers[name]?.takeIf { it.isNotBlank() }
        }
    }

    private fun fail(message: String, logId: String?) {
        synchronized(lock) {
            if (terminal || cancelled) return
            terminal = true
            pendingAudio.clear()
            channel.trySend(SpeechEvent.Failure(message, logId ?: this.logId))
            channel.close()
        }
        webSocket?.cancel()
    }
}
