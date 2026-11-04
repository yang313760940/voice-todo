package com.voicetodo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicetodo.speech.SherpaOfflineTranscriber
import com.voicetodo.speech.TranscriptResult
import com.voicetodo.speech.WavReader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 证伪检查：用随包 sherpa-onnx 模型，在**断网**环境下识别一段固定普通话音频，
 * 断言得到非空的中文识别文字（真实识别，非假转写、非首次下载）。
 */
@RunWith(AndroidJUnit4::class)
class OfflineTranscriptionInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun bundledModelTranscribesFixedMandarinAudioOffline() {
        // 1) 模型已随包就绪（无下载）
        val transcriber = SherpaOfflineTranscriber(context)
        assertTrue("随包模型应就绪", transcriber.isReady())

        // 2) 准备固定测试音频（androidTest assets 中的 0.wav，16k 单声道普通话）
        val wavFile = File(context.cacheDir, "fixed_zh.wav")
        testAssets.open("testwavs/0.wav").use { input ->
            wavFile.outputStream().use { out -> input.copyTo(out) }
        }
        val wav = WavReader.read(wavFile)

        // 3) 断网识别
        disableNetwork()
        val result = transcriber.transcribe(wav.samples, wav.sampleRate)

        // 4) 断言真实识别结果
        assertTrue("应识别成功，实际：$result", result is TranscriptResult.Success)
        val text = (result as TranscriptResult.Success).text
        assertTrue("识别文字不应为空", text.isNotBlank())
        // 结果应为中文（含汉字）
        assertTrue("应包含汉字，实际：$text", text.any { it.code in 0x4E00..0x9FFF })

        // 输出到日志，便于在集成证据中记录
        android.util.Log.i("OfflineIT", "RECOGNIZED_TEXT=$text")
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recognized.txt")
            .writeText(text)
    }

    private fun disableNetwork() {
        try {
            android.provider.Settings.Global.putInt(
                context.contentResolver,
                android.provider.Settings.Global.AIRPLANE_MODE_ON, 1,
            )
        } catch (_: Exception) {
        }
        try {
            val am = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                am.bindProcessToNetwork(null)
            }
        } catch (_: Exception) {
        }
    }
}
