package com.voicetodo.speech

import java.util.UUID
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
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class OkHttpSpeechStreamFactory(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = DoubaoSpeechSettings.ENDPOINT,
) : SpeechStreamFactory {
    override fun open(settings: DoubaoSpeechSettings): SpeechStream =
        DoubaoSpeechStream(settings, client, endpoint)

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
 * 豆包实时语音识别适配器。
 *
 * 音频分片只保存在内存队列中，WebSocket 建立后立即发送；不落盘、不重试。
 * 服务端返回的 [X-Tt-Logid] 只用于错误提示和排查。
 */
class DoubaoSpeechStream(
    private val settings: DoubaoSpeechSettings,
    private val client: OkHttpClient,
    private val endpoint: String = DoubaoSpeechSettings.ENDPOINT,
) : SpeechStream {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
    override val events: Flow<SpeechEvent> = channel.receiveAsFlow()

    private val lock = Any()
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var webSocket: WebSocket? = null
    private var opened = false
    private var finishRequested = false
    private var cancelled = false
    private var terminal = false
    private var nextSequence = 1
    private var logId: String? = null
    private var language = "zh-CN"
    private val requestId = UUID.randomUUID().toString()
    private val connectId = UUID.randomUUID().toString()

    override fun start(language: String) {
        this.language = language
        if (settings.apiKey.isBlank()) {
            fail("豆包语音 API Key 为空；请先在高级设置中填写", null)
            return
        }
        val request = try {
            Request.Builder()
                .url(endpoint)
                .header("X-Api-Key", settings.apiKey)
                .header("X-Api-Resource-Id", settings.resourceId)
                .header("X-Api-Request-Id", requestId)
                .header("X-Api-Connect-Id", connectId)
                .build()
        } catch (e: Exception) {
            fail("豆包语音连接地址无效", null)
            return
        }
        webSocket = client.newWebSocket(request, listener)
    }

    override fun pushPcm(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        val copy = pcm.copyOf(length)
        synchronized(lock) {
            if (cancelled || terminal || finishRequested) return
            if (!opened) {
                pendingAudio.addLast(copy)
            } else {
                sendAudio(copy, last = false)
            }
        }
    }

    override fun finish() {
        synchronized(lock) {
            if (cancelled || terminal || finishRequested) return
            finishRequested = true
            if (opened) sendAudio(ByteArray(0), last = true)
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
            logId = response.header("X-Tt-Logid") ?: response.header("X-Tt-LogId")
            channel.trySend(SpeechEvent.Connected(logId))
            synchronized(lock) {
                if (cancelled || terminal) return
                opened = true
                webSocket.send(
                    DoubaoProtocol.buildFullClientRequest(fullRequestJson(), nextSequence)
                        .toByteString(),
                )
                while (pendingAudio.isNotEmpty()) {
                    sendAudio(pendingAudio.removeFirst(), last = false)
                }
                if (finishRequested) sendAudio(ByteArray(0), last = true)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val response = try {
                DoubaoProtocol.parseResponse(bytes.toByteArray())
            } catch (e: Exception) {
                fail("豆包语音响应无法解析：${e.message ?: "格式错误"}", logId)
                return
            }
            if (response.messageType == DoubaoProtocol.MESSAGE_SERVER_ERROR_RESPONSE) {
                val detail = response.message ?: response.rawJson.orEmpty()
                fail(
                    "豆包语音识别失败（${response.code}）：" +
                        detail.ifBlank { "服务端未返回原因" },
                    logId,
                )
                return
            }
            if (response.last && finishRequested) {
                val finalText = response.text.orEmpty()
                synchronized(lock) {
                    if (terminal) return
                    terminal = true
                    channel.trySend(SpeechEvent.Final(finalText))
                    channel.close()
                }
                webSocket.close(1000, "done")
            } else {
                val text = response.text ?: return
                channel.trySend(SpeechEvent.Partial(text))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 该接口只返回二进制帧；文本帧视为协议异常，但不泄露连接信息。
            fail("豆包语音返回了非预期的文本消息", logId)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!terminal && !cancelled) {
                fail("语音识别连接已中断，已识别的文字可能不完整", logId)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (cancelled || terminal) return
            val handshakeLogId = response?.header("X-Tt-Logid")
                ?: response?.header("X-Tt-LogId")
                ?: logId
            val code = response?.code
            val message = when {
                code == 401 || code == 403 -> {
                    "豆包语音鉴权失败（$code）：请检查 API Key，或确认资源 ID 已开通。" +
                        "未开通 2.0 小时版可在高级设置切换到 1.0 小时版后重试"
                }
                code != null -> "豆包语音连接失败（HTTP $code）"
                else -> "豆包语音连接失败：${t.message ?: "网络错误"}"
            }
            fail("$message；已识别的文字可能不完整", handshakeLogId)
        }
    }

    private fun sendAudio(pcm: ByteArray, last: Boolean) {
        val socket = webSocket ?: return
        nextSequence += 1
        val sequence = if (last) -nextSequence else nextSequence
        socket.send(DoubaoProtocol.buildAudioRequest(pcm, sequence, last).toByteString())
    }

    private fun fullRequestJson(): String {
        val json = JSONObject()
            .put("user", JSONObject().put("uid", connectId))
            .put(
                "audio",
                JSONObject()
                    .put("format", "pcm")
                    .put("codec", "raw")
                    .put("rate", 16000)
                    .put("bits", 16)
                    .put("channel", 1)
                    .put("language", language),
            )
            .put(
                "request",
                JSONObject()
                    .put("model_name", "bigmodel")
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("enable_ddc", true)
                    .put("show_utterances", true)
                    .put("enable_nonstream", false),
            )
        return json.toString()
    }

    private fun fail(message: String, logId: String?) {
        synchronized(lock) {
            if (terminal || cancelled) return
            terminal = true
            pendingAudio.clear()
            channel.trySend(SpeechEvent.Failure(message, logId))
            channel.close()
        }
        webSocket?.cancel()
    }
}
