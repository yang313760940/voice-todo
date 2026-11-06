package com.voicetodo.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** 把 16kHz 单声道 16-bit PCM 分片直接交给转写接缝；不保留副本、不落盘。 */
interface SpeechRecorder {
    val sampleRate: Int

    fun start(onChunk: (ByteArray) -> Unit): Boolean

    fun stop()

    fun cancel()

    fun release()
}

interface SpeechRecorderFactory {
    fun create(): SpeechRecorder
}

object DefaultSpeechRecorderFactory : SpeechRecorderFactory {
    override fun create(): SpeechRecorder = StreamingPcmRecorder()
}

class StreamingPcmRecorder : SpeechRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    override val sampleRate = 16000

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(onChunk: (ByteArray) -> Unit): Boolean {
        if (isRecording || audioRecord != null) return false
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (_: Exception) {
            record.release()
            return false
        }
        audioRecord = record
        isRecording = true

        recordingThread = Thread {
            val chunk = ByteArray(minBuf)
            while (isRecording) {
                val count = record.read(chunk, 0, chunk.size)
                if (count > 0) onChunk(chunk.copyOf(count))
            }
        }.also {
            it.name = "voice-todo-streaming-recorder"
            it.start()
        }
        return true
    }

    @Synchronized
    override fun stop() {
        finish()
    }

    @Synchronized
    override fun cancel() {
        finish()
    }

    @Synchronized
    override fun release() {
        finish()
    }

    private fun finish() {
        if (!isRecording && audioRecord == null) return
        isRecording = false
        val record = audioRecord
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            recordingThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        record?.release()
        audioRecord = null
        recordingThread = null
    }
}
