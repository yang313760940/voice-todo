package com.voicetodo.speech

/** 转写结果状态，供界面区分“真实识别成功 / 失败”而非造假。 */
sealed interface TranscriptResult {
    data class Success(val text: String) : TranscriptResult
    data class Failure(val message: String) : TranscriptResult
}

/**
 * 离线转写接缝：从一段 16kHz 单声道 PCM float 采样识别普通话文字。
 * 真实实现见 [SherpaOfflineTranscriber]（随包 sherpa-onnx 模型）；测试用替身。
 */
interface OfflineTranscriber {
    fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptResult
    /** 模型是否已随包就绪（不依赖首次下载）。 */
    fun isReady(): Boolean
}
