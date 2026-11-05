package com.voicetodo.speech

/** 本机语音引擎是否具备播报中文标题的条件。 */
enum class SpeechAvailability {
    Ready,
    MissingLanguageData,
    UnsupportedLanguage,
    EngineUnavailable,
}

/**
 * 到点提示的呈现决定：
 * - [Silent]：不朗读，沿用现有提示音/持续响铃；
 * - [Speak]：用本机语音朗读标题；
 * - [Fallback]：朗读已开启但资源不可用，播放提示音并展示原因。
 */
sealed interface SpeechDecision {
    data object Silent : SpeechDecision
    data object Speak : SpeechDecision
    data class Fallback(val reason: String) : SpeechDecision
}

object SpeechFallbackReason {
    const val MISSING_DATA = "本机缺少中文语音数据，已改用提示音"
    const val UNSUPPORTED_LANGUAGE = "系统语音引擎不支持中文，已改用提示音"
    const val ENGINE_UNAVAILABLE = "未找到可用的本机语音引擎，已改用提示音"
    const val START_FAILED = "语音播报启动失败，已改用提示音"
}

/**
 * 纯策略：朗读开关、锁屏状态、锁屏朗读选择与资源可用性共同决定如何提示。
 *
 * 锁屏且未明确允许锁屏朗读时不尝试发声，避免在用户未同意时读出正文；
 * 这种情况下仍由既有提示音通知提醒，不额外展示回退原因。
 */
fun decideReminderSpeech(
    readAloud: Boolean,
    readAloudWhenLocked: Boolean,
    deviceLocked: Boolean,
    availability: SpeechAvailability,
): SpeechDecision {
    if (!readAloud) return SpeechDecision.Silent
    if (deviceLocked && !readAloudWhenLocked) return SpeechDecision.Silent
    return when (availability) {
        SpeechAvailability.Ready -> SpeechDecision.Speak
        SpeechAvailability.MissingLanguageData ->
            SpeechDecision.Fallback(SpeechFallbackReason.MISSING_DATA)
        SpeechAvailability.UnsupportedLanguage ->
            SpeechDecision.Fallback(SpeechFallbackReason.UNSUPPORTED_LANGUAGE)
        SpeechAvailability.EngineUnavailable ->
            SpeechDecision.Fallback(SpeechFallbackReason.ENGINE_UNAVAILABLE)
    }
}

/** 到点朗读的本机语音边界；测试用假实现替代真实 TextToSpeech。 */
interface ReminderSpeaker {
    /** 初始化引擎并确认中文资源可用；必须可重复调用。 */
    suspend fun prepare(): SpeechAvailability

    /** 开始朗读；`onCompleted` 在朗读结束或失败时回调。返回是否成功入队。 */
    suspend fun speak(text: String, onCompleted: () -> Unit = {}): Boolean

    fun stop()

    fun release() = stop()
}
