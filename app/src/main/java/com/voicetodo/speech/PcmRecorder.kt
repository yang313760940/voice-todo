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
class PcmRecorder {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private val buffer = ByteArrayOutputStream()

    val sampleRate = 16000

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true
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
        audioRecord = record
        buffer.reset()
        isRecording = true
        record.startRecording()

        recordingThread = Thread {
            val chunk = ByteArray(minBuf)
            val rec = audioRecord ?: return@Thread
            while (isRecording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                }
            }
        }.also { it.start() }
        return true
    }

    /** 停止并返回 float 采样（[-1,1]）。 */
    fun stop(): FloatArray {
        if (!isRecording) return FloatArray(0)
        isRecording = false
        try {
            recordingThread?.join(500)
        } catch (_: InterruptedException) {
        }
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        recordingThread = null

        val pcm: ByteArray
        synchronized(buffer) { pcm = buffer.toByteArray() }
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

    fun release() {
        if (isRecording) stop()
        synchronized(buffer) { buffer.reset() }
    }
}
