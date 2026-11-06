package com.voicetodo.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 可选的真实服务冒烟测试：只有设置 DOUBAO_SPEECH_API_KEY 时才执行。
 * 日志只输出资源 ID、logid 和结果，不输出密钥。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubaoLivetest {

    @Test
    fun `real handshake and short silence smoke`() = runBlocking<Unit> {
        val apiKey = System.getenv("DOUBAO_SPEECH_API_KEY").orEmpty()
        assumeTrue("未提供 DOUBAO_SPEECH_API_KEY，跳过真实服务冒烟", apiKey.isNotBlank())

        val resources = listOf(
            DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
            DoubaoSpeechSettings.LEGACY_RESOURCE_ID,
        )
        var succeeded = false
        val failures = mutableListOf<String>()
        for (resource in resources) {
            val outcome = smoke(apiKey, resource)
            println("DOUBAO_LIVE resource=$resource ${outcome.report()}")
            if (outcome.ok) {
                succeeded = true
                break
            }
            failures += "$resource: ${outcome.report()}"
        }
        assertTrue(
            "真实服务冒烟未成功：${failures.joinToString(" | ")}",
            succeeded,
        )
    }

    private suspend fun smoke(apiKey: String, resourceId: String): Outcome {
        val stream = DoubaoSpeechStream(
            settings = DoubaoSpeechSettings(
                apiKey = apiKey,
                resourceId = resourceId,
                uploadAcknowledged = true,
            ),
            client = OkHttpSpeechStreamFactory.defaultClient(),
        )
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            stream.events.collect { events.send(it) }
        }
        var logId: String? = null
        return try {
            stream.start("zh-CN")
            val first = withTimeoutOrNull(15_000) { events.receive() }
                ?: return Outcome(false, "等待握手超时")
            if (first is SpeechEvent.Failure) {
                return Outcome(false, "握手失败：${first.message}", first.logId)
            }
            if (first is SpeechEvent.Connected) logId = first.logId

            repeat(10) {
                stream.pushPcm(ByteArray(3200))
            }
            stream.finish()
            var final: SpeechEvent.Final? = null
            var failure: SpeechEvent.Failure? = null
            while (final == null && failure == null) {
                val event = withTimeoutOrNull(20_000) { events.receive() } ?: break
                when (event) {
                    is SpeechEvent.Connected -> logId = event.logId
                    is SpeechEvent.Partial -> Unit
                    is SpeechEvent.Final -> final = event
                    is SpeechEvent.Failure -> failure = event
                }
            }
            when {
                final != null -> Outcome(true, "最终文字=\"${final.text}\"", logId)
                failure != null -> Outcome(
                    false,
                    "识别失败：${failure.message}",
                    failure.logId ?: logId,
                )
                else -> Outcome(false, "等待最终结果超时", logId)
            }
        } finally {
            stream.cancel()
            scope.cancel()
            events.close()
        }
    }

    private data class Outcome(
        val ok: Boolean,
        val detail: String,
        val logId: String? = null,
    ) {
        fun report(): String = buildString {
            append(if (ok) "ok" else "failed")
            append(" detail=")
            append(detail)
            append(" logid=")
            append(logId ?: "-")
        }
    }
}
