package com.voicetodo.speech

import kotlinx.coroutines.flow.Flow

/** 一次在线转写会话中的事件；文字只用于当前添加流程，不落盘。 */
sealed interface SpeechEvent {
    /** WebSocket 已建立；[logId] 来自服务端响应头，可用于排查且不含密钥。 */
    data class Connected(val logId: String?) : SpeechEvent

    /** 边说边出的中间文字。 */
    data class Partial(val text: String) : SpeechEvent

    /** 用户结束录音后服务端返回的最终文字。 */
    data class Final(val text: String) : SpeechEvent

    /** 失败时保留已经得到的文字；调用方不得自动重试或自动保存。 */
    data class Failure(val message: String, val logId: String? = null) : SpeechEvent
}

/**
 * 在线语音转写接缝：开始（含语言）、推送 16kHz 单声道 PCM 分片、结束、取消。
 * 实现不得把音频写入磁盘，也不得发送到所选服务之外。
 */
interface SpeechStream {
    val events: Flow<SpeechEvent>

    fun start(language: String = "zh-CN")

    fun pushPcm(pcm: ByteArray, length: Int = pcm.size)

    /** 结束输入并等待最终文字；重复调用应被忽略。 */
    fun finish()

    /** 立即放弃本次会话；不再发送最后一包，也不再等待结果。 */
    fun cancel()
}

interface SpeechStreamFactory {
    fun open(settings: DoubaoSpeechSettings): SpeechStream
}
