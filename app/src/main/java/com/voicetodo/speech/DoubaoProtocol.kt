package com.voicetodo.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

/**
 * 豆包大模型流式语音识别的二进制协议。
 *
 * 头部 4 字节：
 * - byte0：高 4 位协议版本（1），低 4 位头部长度（1，即 4 字节）
 * - byte1：高 4 位消息类型，低 4 位 flags
 * - byte2：高 4 位序列化方式（JSON=1），低 4 位压缩方式（GZIP=1）
 * - byte3：保留
 *
 * 带序列号的消息在头部后依次是大端 int32 sequence、大端 uint32 payload 长度、
 * 以及 GZIP 压缩后的 payload。
 */
object DoubaoProtocol {
    const val MESSAGE_FULL_CLIENT_REQUEST = 0x1
    const val MESSAGE_AUDIO_ONLY_CLIENT_REQUEST = 0x2
    const val MESSAGE_FULL_SERVER_RESPONSE = 0x9
    const val MESSAGE_SERVER_ERROR_RESPONSE = 0xF

    const val FLAG_NO_SEQUENCE = 0x0
    const val FLAG_POSITIVE_SEQUENCE = 0x1
    const val FLAG_LAST_PACKET = 0x2
    const val FLAG_NEGATIVE_WITH_SEQUENCE = 0x3
    const val FLAG_HAS_EVENT = 0x4

    const val SERIALIZATION_NONE = 0x0
    const val SERIALIZATION_JSON = 0x1

    const val COMPRESSION_NONE = 0x0
    const val COMPRESSION_GZIP = 0x1

    fun buildFullClientRequest(payloadJson: String, sequence: Int): ByteArray {
        val compressed = gzip(payloadJson.toByteArray(Charsets.UTF_8))
        return frame(
            messageType = MESSAGE_FULL_CLIENT_REQUEST,
            flags = FLAG_POSITIVE_SEQUENCE,
            serialization = SERIALIZATION_JSON,
            compression = COMPRESSION_GZIP,
            sequence = sequence,
            payload = compressed,
        )
    }

    fun buildAudioRequest(pcm: ByteArray, sequence: Int, last: Boolean): ByteArray {
        val compressed = gzip(pcm)
        return frame(
            messageType = MESSAGE_AUDIO_ONLY_CLIENT_REQUEST,
            flags = if (last) FLAG_NEGATIVE_WITH_SEQUENCE else FLAG_POSITIVE_SEQUENCE,
            serialization = SERIALIZATION_NONE,
            compression = COMPRESSION_GZIP,
            sequence = sequence,
            payload = compressed,
        )
    }

    /** 测试和本地假 WebSocket 服务也使用它构造服务端响应。 */
    fun buildServerResponse(
        payloadJson: String,
        sequence: Int,
        last: Boolean,
        compression: Int = COMPRESSION_GZIP,
    ): ByteArray {
        val payload = if (compression == COMPRESSION_GZIP) {
            gzip(payloadJson.toByteArray(Charsets.UTF_8))
        } else {
            payloadJson.toByteArray(Charsets.UTF_8)
        }
        return frame(
            messageType = MESSAGE_FULL_SERVER_RESPONSE,
            flags = FLAG_POSITIVE_SEQUENCE or if (last) FLAG_LAST_PACKET else 0,
            serialization = SERIALIZATION_JSON,
            compression = compression,
            sequence = sequence,
            payload = payload,
        )
    }

    fun buildServerError(code: Int, message: String): ByteArray {
        val payload = message.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + 4 + 4 + payload.size)
        buffer.put(header(MESSAGE_SERVER_ERROR_RESPONSE, FLAG_NO_SEQUENCE, SERIALIZATION_JSON, COMPRESSION_NONE))
        buffer.putInt(code)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun parseResponse(bytes: ByteArray): DoubaoResponse {
        require(bytes.size >= 4) { "豆包响应长度不足 4 字节" }
        val headerWords = bytes[0].toInt() and 0x0F
        require(headerWords >= 1) { "豆包响应头部长度无效" }
        var offset = headerWords * 4
        require(offset <= bytes.size) { "豆包响应头部不完整" }

        val messageType = (bytes[1].toInt() shr 4) and 0x0F
        val flags = bytes[1].toInt() and 0x0F
        val serialization = (bytes[2].toInt() shr 4) and 0x0F
        val compression = bytes[2].toInt() and 0x0F

        var sequence: Int? = null
        var event: Int? = null
        if (flags and FLAG_POSITIVE_SEQUENCE != 0) {
            require(offset + 4 <= bytes.size) { "豆包响应缺少 sequence" }
            sequence = ByteBuffer.wrap(bytes, offset, 4).int
            offset += 4
        }
        val last = flags and FLAG_LAST_PACKET != 0
        if (flags and FLAG_HAS_EVENT != 0) {
            require(offset + 4 <= bytes.size) { "豆包响应缺少 event" }
            event = ByteBuffer.wrap(bytes, offset, 4).int
            offset += 4
        }

        var code = 0
        var payloadSize = 0
        if (messageType == MESSAGE_FULL_SERVER_RESPONSE) {
            require(offset + 4 <= bytes.size) { "豆包响应缺少 payload 长度" }
            payloadSize = ByteBuffer.wrap(bytes, offset, 4).int
            offset += 4
        } else if (messageType == MESSAGE_SERVER_ERROR_RESPONSE) {
            require(offset + 8 <= bytes.size) { "豆包错误响应不完整" }
            code = ByteBuffer.wrap(bytes, offset, 4).int
            payloadSize = ByteBuffer.wrap(bytes, offset + 4, 4).int
            offset += 8
        }

        require(payloadSize >= 0) { "豆包响应 payload 长度无效" }
        require(offset + payloadSize <= bytes.size) { "豆包响应 payload 不完整" }
        var payload = bytes.copyOfRange(offset, offset + payloadSize)
        if (compression == COMPRESSION_GZIP && payload.isNotEmpty()) {
            payload = gunzip(payload)
        }

        var rawJson: String? = null
        var text: String? = null
        var message: String? = null
        if (serialization == SERIALIZATION_JSON && payload.isNotEmpty()) {
            rawJson = String(payload, Charsets.UTF_8)
            val json = runCatching { JSONObject(rawJson) }.getOrNull()
            if (json != null) {
                message = json.optString("message").orEmpty().takeIf { it.isNotBlank() }
                    ?: json.optJSONObject("error")
                        ?.optString("message")
                        .orEmpty()
                        .takeIf { it.isNotBlank() }
                val result = json.optJSONObject("result")
                text = result?.optString("text").orEmpty().takeIf { it.isNotBlank() }
                    ?: result?.optJSONArray("utterances")?.let { utterances ->
                        buildList {
                            for (index in 0 until utterances.length()) {
                                val part = utterances.optJSONObject(index)
                                    ?.optString("text")
                                    .orEmpty()
                                if (part.isNotBlank()) add(part)
                            }
                        }.joinToString("")
                    }?.takeIf { it.isNotBlank() }
            } else if (messageType == MESSAGE_SERVER_ERROR_RESPONSE) {
                // 服务端错误消息约定为 UTF-8 文本，不一定是 JSON。
                message = rawJson
            }
        }

        return DoubaoResponse(
            messageType = messageType,
            flags = flags,
            sequence = sequence,
            event = event,
            last = last,
            code = code,
            text = text,
            message = message,
            rawJson = rawJson,
        )
    }

    private fun frame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        sequence: Int,
        payload: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 4 + 4 + payload.size)
        buffer.put(header(messageType, flags, serialization, compression))
        buffer.putInt(sequence)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    private fun header(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
    ): ByteArray = byteArrayOf(
        0x11,
        ((messageType shl 4) or flags).toByte(),
        ((serialization shl 4) or compression).toByte(),
        0x00,
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

data class DoubaoResponse(
    val messageType: Int,
    val flags: Int,
    val sequence: Int?,
    val event: Int?,
    val last: Boolean,
    val code: Int,
    val text: String?,
    val message: String?,
    val rawJson: String?,
)
