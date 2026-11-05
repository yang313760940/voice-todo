package com.voicetodo.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 系统 TextToSpeech 边界。
 *
 * 只使用设备上已安装的语音引擎与语音数据，不调用任何网络 TTS 接口；
 * 引擎初始化失败、中文数据缺失或不支持中文时由调用方回退提示音。
 */
class AndroidReminderSpeaker(context: Context) : ReminderSpeaker {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var availability: SpeechAvailability? = null
    private var released = false

    override suspend fun prepare(): SpeechAvailability {
        availability?.let { return it }
        if (released) return SpeechAvailability.EngineUnavailable
        val initialized = CompletableDeferred<Boolean>()
        val created = try {
            TextToSpeech(appContext) { status ->
                if (!initialized.isCompleted) initialized.complete(status == TextToSpeech.SUCCESS)
            }
        } catch (e: Exception) {
            availability = SpeechAvailability.EngineUnavailable
            return SpeechAvailability.EngineUnavailable
        }
        engine = created
        val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialized.await() } == true
        val result = if (!ready) {
            SpeechAvailability.EngineUnavailable
        } else {
            runCatching {
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                when (created.setLanguage(Locale.CHINESE)) {
                    TextToSpeech.LANG_MISSING_DATA -> SpeechAvailability.MissingLanguageData
                    TextToSpeech.LANG_NOT_SUPPORTED -> SpeechAvailability.UnsupportedLanguage
                    else -> SpeechAvailability.Ready
                }
            }.getOrElse { SpeechAvailability.EngineUnavailable }
        }
        availability = result
        if (result != SpeechAvailability.Ready) {
            runCatching { created.shutdown() }
            engine = null
        }
        return result
    }

    override suspend fun speak(text: String, onCompleted: () -> Unit): Boolean {
        val tts = engine ?: return false
        return try {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    mainHandler.post(onCompleted)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post(onCompleted)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    mainHandler.post(onCompleted)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    override fun stop() {
        runCatching { engine?.stop() }
    }

    override fun release() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        released = true
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 3_000L
        const val UTTERANCE_ID = "voicetodo-reminder"
    }
}
