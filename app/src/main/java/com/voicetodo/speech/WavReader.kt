package com.voicetodo.speech

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/** 读取 16-bit PCM WAV 为 float 采样；用于把随包固定测试音频喂给识别器。 */
object WavReader {
    data class Wav(val samples: FloatArray, val sampleRate: Int)

    fun read(file: File): Wav {
        DataInputStream(FileInputStream(file).buffered()).use { dis ->
            // RIFF header
            val riff = ByteArray(4); dis.readFully(riff)
            dis.readInt() // chunk size
            val wave = ByteArray(4); dis.readFully(wave)
            var sampleRate = 16000
            var channels = 1
            var bitsPerSample = 16
            var data: ByteArray? = null

            while (true) {
                val idBytes = ByteArray(4)
                val read = dis.read(idBytes)
                if (read < 4) break
                val id = String(idBytes)
                val size = Integer.reverseBytes(dis.readInt())
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size); dis.readFully(fmt)
                        val le = java.nio.ByteBuffer.wrap(fmt).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        // audioFormat(2) channels(2) sampleRate(4) ...
                        le.short; channels = le.short.toInt()
                        sampleRate = le.int
                        le.int; le.short; bitsPerSample = le.short.toInt()
                    }
                    "data" -> {
                        data = ByteArray(size); dis.readFully(data); break
                    }
                    else -> {
                        // 跳过（可能奇数填充）
                        val skip = size + (size and 1)
                        var remaining = skip.toLong()
                        while (remaining > 0) {
                            val s = dis.skip(remaining)
                            if (s <= 0) break
                            remaining -= s
                        }
                    }
                }
            }
            val bytes = data ?: ByteArray(0)
            val count = bytes.size / 2
            val samples = FloatArray(count)
            val le = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                samples[i] = le.short / 32768.0f
            }
            return Wav(samples, sampleRate)
        }
    }
}
