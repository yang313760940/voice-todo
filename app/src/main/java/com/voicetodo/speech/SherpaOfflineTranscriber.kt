package com.voicetodo.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * 随包 sherpa-onnx 离线普通话识别（Paraformer-zh-small）。
 *
 * 模型与词表在构建期放入 app/src/main/assets/sherpa/，首次启动**无需下载**。
 * 此处先把 assets 中的模型复制到 filesDir（一次性），再以文件路径初始化识别器。
 */
class SherpaOfflineTranscriber(private val context: Context) : OfflineTranscriber {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private fun modelDir(): File {
        val dir = File(context.filesDir, "sherpa-model")
        if (!File(dir, "model.int8.onnx").exists()) {
            dir.mkdirs()
            copyAsset("sherpa/model.int8.onnx", File(dir, "model.int8.onnx"))
            copyAsset("sherpa/tokens.txt", File(dir, "tokens.txt"))
        }
        return dir
    }

    private fun copyAsset(assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
    }

    @Synchronized
    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val dir = modelDir()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        )
        // 模型用绝对文件路径加载，按 sherpa-onnx 要求 assetManager 必须为 null，
        // 否则会误走 assets 解析导致 ReadFile 失败（见 sherpa-onnx #2562）。
        val r = OfflineRecognizer(null, config)
        recognizer = r
        return r
    }

    override fun isReady(): Boolean {
        return try {
            context.assets.open("sherpa/model.int8.onnx").use { true }
        } catch (e: Exception) {
            false
        }
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptResult {
        return try {
            val r = ensureRecognizer()
            val stream = r.createStream()
            stream.acceptWaveform(samples, sampleRate)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            stream.release()
            if (text.isEmpty()) {
                TranscriptResult.Failure("未识别到语音内容")
            } else {
                TranscriptResult.Success(text)
            }
        } catch (e: Throwable) {
            Log.e("SherpaOffline", "transcribe failed", e)
            TranscriptResult.Failure("识别失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
