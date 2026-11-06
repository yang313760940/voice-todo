package com.voicetodo.speech

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
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
class DoubaoSpeechStreamTest {
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
    fun `adapter sends headers protocol frames and emits partial then final text`() = runBlocking<Unit> {
        val received = LinkedBlockingQueue<ByteArray>()
        val serverSocket = LinkedBlockingQueue<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeServerSocket = webSocket
                    serverSocket.add(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received.add(bytes.toByteArray())
                }
            }),
        )
        server.start()
        val stream = DoubaoSpeechStream(
            settings = DoubaoSpeechSettings("test-key", uploadAcknowledged = true),
            client = client,
            endpoint = server.url("/asr").toString().replace("http://", "ws://"),
        )
        val events = collectEvents(stream)

        try {
            stream.start("zh-CN")
            stream.pushPcm(byteArrayOf(1, 2, 3, 4))
            stream.finish()

            val upgrade = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(upgrade)
            assertEquals("test-key", upgrade!!.getHeader("X-Api-Key"))
            assertEquals(
                DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
                upgrade.getHeader("X-Api-Resource-Id"),
            )
            assertTrue(!upgrade.getHeader("X-Api-Request-Id").isNullOrBlank())
            assertTrue(!upgrade.getHeader("X-Api-Connect-Id").isNullOrBlank())

            val full = received.poll(5, TimeUnit.SECONDS)!!
            assertEquals(0x11, full[1].toInt() and 0xFF)
            val audio = received.poll(5, TimeUnit.SECONDS)!!
            assertEquals(0x21, audio[1].toInt() and 0xFF)
            val last = received.poll(5, TimeUnit.SECONDS)!!
            assertEquals(0x23, last[1].toInt() and 0xFF)

            val socket = serverSocket.poll(5, TimeUnit.SECONDS)!!
            socket.send(
                DoubaoProtocol.buildServerResponse(
                    """{"result":{"text":"明天下午"}}""",
                    sequence = 2,
                    last = false,
                ).toByteString(),
            )
            socket.send(
                DoubaoProtocol.buildServerResponse(
                    """{"result":{"text":"明天下午三点开会"}}""",
                    sequence = 3,
                    last = true,
                ).toByteString(),
            )

            assertEquals(SpeechEvent.Connected::class.java, events.receive().javaClass)
            assertEquals(SpeechEvent.Partial("明天下午"), events.receive())
            assertEquals(SpeechEvent.Final("明天下午三点开会"), events.receive())
            socket.close(1000, "done")
        } finally {
            stream.cancel()
            events.cancel()
        }
    }

    @Test
    fun `handshake rejection reports 403 and logid without exposing key`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("X-Tt-Logid", "fake-logid"),
        )
        server.start()
        val stream = DoubaoSpeechStream(
            settings = DoubaoSpeechSettings("secret-test-key", uploadAcknowledged = true),
            client = client,
            endpoint = server.url("/asr").toString().replace("http://", "ws://"),
        )
        val events = collectEvents(stream)

        try {
            stream.start("zh-CN")
            val failure = events.receive() as SpeechEvent.Failure
            assertTrue(failure.message.contains("403"))
            assertEquals("fake-logid", failure.logId)
            assertTrue(!failure.message.contains("secret-test-key"))
        } finally {
            stream.cancel()
            events.cancel()
        }
    }

    @Test
    fun `interruption after partial text emits failure`() = runBlocking<Unit> {
        val serverSocket = LinkedBlockingQueue<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeServerSocket = webSocket
                    serverSocket.add(webSocket)
                }
            }),
        )
        server.start()
        val stream = DoubaoSpeechStream(
            settings = DoubaoSpeechSettings("test-key", uploadAcknowledged = true),
            client = client,
            endpoint = server.url("/asr").toString().replace("http://", "ws://"),
        )
        val events = collectEvents(stream)

        try {
            stream.start("zh-CN")
            stream.pushPcm(byteArrayOf(1, 2))
            val socket = serverSocket.poll(5, TimeUnit.SECONDS)!!
            socket.send(
                DoubaoProtocol.buildServerResponse(
                    """{"result":{"text":"买牛奶"}}""",
                    sequence = 2,
                    last = false,
                ).toByteString(),
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

    private fun CoroutineScope.collectEvents(stream: SpeechStream): Channel<SpeechEvent> {
        val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
        val job: Job = launch(Dispatchers.IO) {
            stream.events.collect { channel.send(it) }
        }
        channel.invokeOnClose { job.cancel() }
        return channel
    }
}
