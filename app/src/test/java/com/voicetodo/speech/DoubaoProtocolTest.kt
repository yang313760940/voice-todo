package com.voicetodo.speech

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubaoProtocolTest {

    @Test
    fun `full client request uses json gzip header and positive sequence`() {
        val frame = DoubaoProtocol.buildFullClientRequest("""{"hello":"世界"}""", sequence = 1)

        assertEquals(0x11, frame[0].toInt() and 0xFF)
        assertEquals(0x11, frame[1].toInt() and 0xFF)
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        assertEquals(0x00, frame[3].toInt() and 0xFF)
        assertEquals(1, ByteBuffer.wrap(frame, 4, 4).int)

        val payload = frame.copyOfRange(12, frame.size)
        val json = String(gunzip(payload), Charsets.UTF_8)
        assertEquals("""{"hello":"世界"}""", json)
    }

    @Test
    fun `audio frames use raw gzip payload and negative sequence for last packet`() {
        val pcm = byteArrayOf(0, 1, 2, 3, -1)
        val normal = DoubaoProtocol.buildAudioRequest(pcm, sequence = 2, last = false)
        assertEquals(0x21, normal[1].toInt() and 0xFF)
        assertEquals(0x01, normal[2].toInt() and 0xFF)
        assertEquals(2, ByteBuffer.wrap(normal, 4, 4).int)
        assertArrayEquals(pcm, gunzip(normal.copyOfRange(12, normal.size)))

        val last = DoubaoProtocol.buildAudioRequest(ByteArray(0), sequence = -3, last = true)
        assertEquals(0x23, last[1].toInt() and 0xFF)
        assertEquals(-3, ByteBuffer.wrap(last, 4, 4).int)
        assertTrue(ByteBuffer.wrap(last, 8, 4).int > 0)
        assertEquals(0, gunzip(last.copyOfRange(12, last.size)).size)
    }

    @Test
    fun `server response parses full text and last flag`() {
        val frame = DoubaoProtocol.buildServerResponse(
            """{"result":{"text":"明天下午三点开会","utterances":[]}}""",
            sequence = 3,
            last = true,
        )

        val response = DoubaoProtocol.parseResponse(frame)

        assertEquals(DoubaoProtocol.MESSAGE_FULL_SERVER_RESPONSE, response.messageType)
        assertEquals(3, response.sequence)
        assertTrue(response.last)
        assertEquals("明天下午三点开会", response.text)
    }

    @Test
    fun `server response falls back to utterance text`() {
        val frame = DoubaoProtocol.buildServerResponse(
            """{"result":{"utterances":[{"text":"买"},{"text":"牛奶"}]}}""",
            sequence = 1,
            last = false,
        )

        val response = DoubaoProtocol.parseResponse(frame)

        assertFalse(response.last)
        assertEquals("买牛奶", response.text)
    }

    @Test
    fun `server error response exposes code and message`() {
        val frame = DoubaoProtocol.buildServerError(45000001, "resource not activated")

        val response = DoubaoProtocol.parseResponse(frame)

        assertEquals(DoubaoProtocol.MESSAGE_SERVER_ERROR_RESPONSE, response.messageType)
        assertEquals(45000001, response.code)
        assertEquals("resource not activated", response.message)
    }

    private fun gunzip(payload: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "expected=${expected.toList()} actual=${actual.toList()}",
            expected.contentEquals(actual),
        )
    }
}
