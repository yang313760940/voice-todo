package com.voicetodo.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * 最小录音器：录制 16kHz 单声道 16-bit PCM（sherpa-onnx 期望的输入格式）。
 * 点击开始录音→再次点击结束，返回 float 采样数组；录音不落盘、不上传。
 */
class PcmRecorder : AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private val buffer = ByteArrayOutputStream()

    override val sampleRate = 16000

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(): Boolean {
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
        buffer.reset()
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
                val n = record.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                }
            }
        }.also {
            it.name = "voice-todo-recorder"
            it.start()
        }
        return true
    }

    /** 停止并返回 float 采样（[-1,1]）。 */
    @Synchronized
    override fun stop(): FloatArray = finish(capture = true)

    @Synchronized
    override fun cancel() {
        finish(capture = false)
    }

    private fun finish(capture: Boolean): FloatArray {
        if (!isRecording && audioRecord == null) {
            synchronized(buffer) { buffer.reset() }
            return FloatArray(0)
        }
        isRecording = false
        val record = audioRecord
        // 先停止 AudioRecord，使阻塞中的 read 尽快返回，再等待采集线程退出。
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

        val pcm: ByteArray
        synchronized(buffer) {
            pcm = if (capture) buffer.toByteArray() else ByteArray(0)
            buffer.reset()
        }
        val count = pcm.size / 2
        val samples = FloatArray(count)
        var i = 0
        while (i < count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            samples[i] = (s / 32768.0f)
            i++
        }
        return samples
    }

    @Synchronized
    override fun release() {
        finish(capture = false)
        synchronized(buffer) { buffer.reset() }
    }
}
