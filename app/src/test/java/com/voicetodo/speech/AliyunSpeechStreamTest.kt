package com.voicetodo.speech

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AliyunSpeechStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var activeServerSocket: WebSocket? = null

    @Before
    fun setup() {
        server = MockWebServer()
        client = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun teardown() {
        runCatching { activeServerSocket?.close(1000, "test done") }
        runCatching { server.shutdown() }
    }

    @Test
    fun `adapter sends Qwen ASR session audio and final transcript`() = runBlocking<Unit> {
        val received = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeServerSocket = webSocket
                    sockets.add(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )
        server.start()
        val stream = AliyunSpeechStream(
            settings = AliyunSpeechSettings(
                apiKey = "fake" + "-aliyun-key",
                uploadAcknowledged = true,
            ),
            client = client,
            endpoint = server.url("/realtime").toString().replace("http://", "ws://"),
        )
        val events = collectEvents(stream)
        val pcm = byteArrayOf(1, 2, 3, 4)

        try {
            stream.start("zh-CN")
            stream.pushPcm(pcm)
            stream.finish()

            val upgrade = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(upgrade)
            assertEquals("qwen3-asr-flash-realtime", upgrade!!.requestUrl!!.queryParameter("model"))
            assertEquals("Bearer fake-aliyun-key", upgrade.getHeader("Authorization"))
            assertEquals("realtime=v1", upgrade.getHeader("OpenAI-Beta"))

            val socket = sockets.poll(5, TimeUnit.SECONDS)!!
            val update = JSONObject(received.poll(5, TimeUnit.SECONDS)!!)
            assertEquals("session.update", update.getString("type"))
            val session = update.getJSONObject("session")
            assertEquals("text", session.getJSONArray("modalities").getString(0))
            assertEquals("pcm", session.getString("input_audio_format"))
            assertEquals(16000, session.getInt("sample_rate"))
            assertEquals("zh", session.getJSONObject("input_audio_transcription").getString("language"))
            assertTrue(session.isNull("turn_detection"))

            socket.send("""{"type":"session.updated"}""")

            val append = JSONObject(received.poll(5, TimeUnit.SECONDS)!!)
            assertEquals("input_audio_buffer.append", append.getString("type"))
            assertArrayEquals(pcm, Base64.getDecoder().decode(append.getString("audio")))
            assertEquals(
                "input_audio_buffer.commit",
                JSONObject(received.poll(5, TimeUnit.SECONDS)!!).getString("type"),
            )
            assertEquals(
                "session.finish",
                JSONObject(received.poll(5, TimeUnit.SECONDS)!!).getString("type"),
            )

            socket.send(
                """{"type":"conversation.item.input_audio_transcription.text","text":"明天下午","stash":"三点"}""",
            )
            socket.send(
                """{"type":"conversation.item.input_audio_transcription.completed","transcript":"明天下午三点开会"}""",
            )
            socket.send("""{"type":"session.finished"}""")

            assertEquals(SpeechEvent.Connected::class.java, events.receive().javaClass)
            assertEquals(SpeechEvent.Partial("明天下午三点"), events.receive())
            assertEquals(SpeechEvent.Final("明天下午三点开会"), events.receive())
        } finally {
            stream.cancel()
            events.cancel()
        }
    }

    @Test
    fun `handshake rejection reports 401 and request id without exposing key`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .addHeader("X-DashScope-Request-Id", "fake-request-id"),
            )
            server.start()
            val stream = AliyunSpeechStream(
                settings = AliyunSpeechSettings(
                apiKey = "fake" + "-aliyun-key",
                    uploadAcknowledged = true,
                ),
                client = client,
                endpoint = server.url("/realtime").toString().replace("http://", "ws://"),
            )
            val events = collectEvents(stream)

            try {
                stream.start("zh-CN")
                val failure = events.receive() as SpeechEvent.Failure
                assertTrue(failure.message.contains("401"))
                assertTrue(failure.message.contains("鉴权"))
                assertEquals("fake-request-id", failure.logId)
                assertTrue(!failure.message.contains("fake-aliyun-key"))
            } finally {
                stream.cancel()
                events.cancel()
            }
        }

    @Test
    fun `interruption after partial text emits failure`() = runBlocking<Unit> {
        val sockets = LinkedBlockingQueue<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeServerSocket = webSocket
                    sockets.add(webSocket)
                }
            }),
        )
        server.start()
        val stream = AliyunSpeechStream(
            settings = AliyunSpeechSettings(
                apiKey = "test-key",
                uploadAcknowledged = true,
            ),
            client = client,
            endpoint = server.url("/realtime").toString().replace("http://", "ws://"),
        )
        val events = collectEvents(stream)

        try {
            stream.start("zh-CN")
            val socket = sockets.poll(5, TimeUnit.SECONDS)!!
            socket.send(
                """{"type":"conversation.item.input_audio_transcription.text","text":"买牛奶","stash":""}""",
            )
            assertEquals(SpeechEvent.Connected::class.java, events.receive().javaClass)
            assertEquals(SpeechEvent.Partial("买牛奶"), events.receive())

            socket.close(1001, "boom")

            val failure = events.receive() as SpeechEvent.Failure
            assertTrue(failure.message.contains("中断"))
        } finally {
            stream.cancel()
            events.cancel()
        }
    }

    @Test
    fun `server error event is mapped and not retried`() = runBlocking<Unit> {
        val sockets = LinkedBlockingQueue<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeServerSocket = webSocket
                    sockets.add(webSocket)
                }
            }),
        )
        server.start()
        val stream = AliyunSpeechStream(
            settings = AliyunSpeechSettings(
                apiKey = "test-key",
                uploadAcknowledged = true,
            ),
            client = client,
            endpoint = server.url("/realtime").toString().replace("http://", "ws://"),
        )
        val events = collectEvents(stream)

        try {
            stream.start("zh-CN")
            val socket = sockets.poll(5, TimeUnit.SECONDS)!!
            socket.send("""{"type":"session.updated"}""")
            socket.send(
                """{"type":"error","error":{"code":"InvalidParameter","message":"模型不可用"}}""",
            )

            assertEquals(SpeechEvent.Connected::class.java, events.receive().javaClass)
            val failure = events.receive() as SpeechEvent.Failure
            assertTrue(failure.message.contains("InvalidParameter"))
            assertTrue(failure.message.contains("模型不可用"))
            assertEquals(1, server.requestCount)
        } finally {
            stream.cancel()
            events.cancel()
        }
    }

    private fun CoroutineScope.collectEvents(stream: SpeechStream): Channel<SpeechEvent> {
        val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
        val job: Job = launch(Dispatchers.IO) {
            stream.events.collect { channel.send(it) }
        }
        channel.invokeOnClose { job.cancel() }
        return channel
    }
}
