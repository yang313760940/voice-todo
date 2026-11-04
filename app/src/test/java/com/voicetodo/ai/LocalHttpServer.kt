package com.voicetodo.ai

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** 仅用于 JVM 测试的最小 HTTP/1.1 服务，避免把测试桩冒充真实供应商。 */
class LocalHttpServer(
    private val responseCode: Int,
    private val responseBody: String,
    private val delayMillis: Long = 0,
) : Closeable {
    data class Request(val path: String, val headers: Map<String, String>, val body: String)

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val worker = thread(isDaemon = true, name = "local-http-test") {
        while (!server.isClosed) {
            try {
                server.accept().use(::handle)
            } catch (_: Exception) {
                // close() 会打断 accept；测试结束不需要额外断言。
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val headerBytes = mutableListOf<Byte>()
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            if (next < 0) return
            headerBytes += next.toByte()
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
        }
        val headerText = headerBytes.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val path = lines.firstOrNull().orEmpty().split(" ").getOrNull(1).orEmpty()
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null
            else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        requests += Request(path, headers, String(body, 0, read, Charsets.UTF_8))
        if (delayMillis > 0) Thread.sleep(delayMillis)
        try {
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            val reason = when (responseCode) {
                200 -> "OK"
                401 -> "Unauthorized"
                500 -> "Internal Server Error"
                else -> "Response"
            }
            socket.getOutputStream().use { out ->
                out.write(
                    (
                        "HTTP/1.1 $responseCode $reason\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1),
                )
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
            // 客户端超时后可能已关闭连接。
        }
    }

    override fun close() {
        server.close()
        worker.join(1_000)
    }
}
