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
        val model = File(dir, "model.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!model.isFile || model.length() == 0L || !tokens.isFile || tokens.length() == 0L) {
            dir.mkdirs()
            copyAsset("sherpa/model.int8.onnx", model)
            copyAsset("sherpa/tokens.txt", tokens)
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
        return assetHasContent("sherpa/model.int8.onnx") && assetHasContent("sherpa/tokens.txt")
    }

    private fun assetHasContent(path: String): Boolean = try {
        context.assets.open(path).use { it.read() >= 0 }
    } catch (_: Exception) {
        false
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptResult {
        if (!isReady()) return TranscriptResult.Failure("随包离线识别模型缺失或不完整")
        if (samples.isEmpty()) return TranscriptResult.Failure("没有录到音频")
        if (sampleRate != 16_000) return TranscriptResult.Failure("录音格式不兼容：需要 16000Hz 单声道音频")
        return try {
            val r = ensureRecognizer()
            val stream = r.createStream()
            val text = try {
                stream.acceptWaveform(samples, sampleRate)
                r.decode(stream)
                r.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
            if (text.isEmpty()) {
                TranscriptResult.Failure("未识别到语音内容")
            } else {
                TranscriptResult.Success(text)
            }
        } catch (e: Throwable) {
            Log.e("SherpaOffline", "transcribe failed", e)
            TranscriptResult.Failure(
                "随包离线识别模型不兼容或无法加载：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }
}
