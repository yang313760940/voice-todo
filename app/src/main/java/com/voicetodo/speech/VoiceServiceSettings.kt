package com.voicetodo.speech

import com.voicetodo.ai.AiSettings

/** 在线语音与事项整理只允许在这两家之间二选一，不跨供应商组合。 */
enum class VoiceProvider(val label: String) {
    Doubao("豆包"),
    Aliyun("阿里云"),
}

data class VoiceLanguage(val code: String, val label: String)

/**
 * 只列出两家实时识别都明确支持的常用语言/方言；默认普通话。
 * 新增语言前应先核对服务端协议是否接受对应语言代码。
 */
object VoiceLanguages {
    val Mandarin = VoiceLanguage("zh-CN", "普通话")
    val Cantonese = VoiceLanguage("yue-CN", "粤语")
    val English = VoiceLanguage("en-US", "英语")

    fun supported(provider: VoiceProvider): List<VoiceLanguage> = when (provider) {
        VoiceProvider.Doubao -> listOf(Mandarin, Cantonese, English)
        VoiceProvider.Aliyun -> listOf(Mandarin, Cantonese, English)
    }

    fun label(code: String): String =
        listOf(Mandarin, Cantonese, English).firstOrNull { it.code == code }?.label ?: code

    fun isSupported(provider: VoiceProvider, code: String): Boolean =
        supported(provider).any { it.code == code }
}

/** 豆包的语音识别与方舟文本整理是两套凭据，必须分别填写。 */
data class DoubaoVoiceCredentials(
    val speechApiKey: String = "",
    val resourceId: String = DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
    val textApiKey: String = "",
    val textModel: String = "",
    val textBaseUrl: String = ARK_BASE_URL,
) {
    fun normalized(): DoubaoVoiceCredentials = copy(
        speechApiKey = speechApiKey.trim(),
        resourceId = resourceId.trim(),
        textApiKey = textApiKey.trim(),
        textModel = textModel.trim(),
        textBaseUrl = textBaseUrl.trim().trimEnd('/'),
    )

    fun speechSettings(uploadAcknowledged: Boolean = false): DoubaoSpeechSettings =
        DoubaoSpeechSettings(
            apiKey = speechApiKey,
            resourceId = resourceId,
            uploadAcknowledged = uploadAcknowledged,
        )

    fun organizerSettings(): AiSettings? {
        val clean = normalized()
        if (clean.textApiKey.isEmpty() || clean.textModel.isEmpty()) return null
        return runCatching {
            AiSettings(clean.textBaseUrl, clean.textModel, clean.textApiKey).validate()
        }.getOrNull()
    }

    companion object {
        const val ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    }
}

/** 阿里云百炼一把 Key 同时覆盖实时识别与文本整理。 */
data class AliyunVoiceCredentials(
    val apiKey: String = "",
    val textModel: String = DEFAULT_TEXT_MODEL,
    val textBaseUrl: String = DASHSCOPE_BASE_URL,
    val speechModel: String = DEFAULT_SPEECH_MODEL,
) {
    fun normalized(): AliyunVoiceCredentials = copy(
        apiKey = apiKey.trim(),
        textModel = textModel.trim(),
        textBaseUrl = textBaseUrl.trim().trimEnd('/'),
        speechModel = speechModel.trim(),
    )

    fun organizerSettings(): AiSettings? {
        val clean = normalized()
        if (clean.apiKey.isEmpty() || clean.textModel.isEmpty()) return null
        return runCatching {
            AiSettings(clean.textBaseUrl, clean.textModel, clean.apiKey).validate()
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_TEXT_MODEL = "qwen-plus"
        const val DEFAULT_SPEECH_MODEL = "qwen3-asr-flash-realtime"
        const val DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
}

/**
 * “我的 → 语音与 AI”的完整配置。
 *
 * 未保存过配置时 [SpeechSettingsStore.load] 返回 null，表示尚未绑定供应商。
 * 两份供应商凭据可同时保留，便于切换；实际语音与整理只使用 [provider] 对应的部分。
 */
data class VoiceServiceSettings(
    val provider: VoiceProvider,
    val language: String = VoiceLanguages.Mandarin.code,
    val doubao: DoubaoVoiceCredentials = DoubaoVoiceCredentials(),
    val aliyun: AliyunVoiceCredentials = AliyunVoiceCredentials(),
    val uploadConsentVersion: Int = 0,
    val acknowledgedProviders: Set<VoiceProvider> = emptySet(),
) {
    fun normalized(): VoiceServiceSettings = copy(
        language = language.trim(),
        doubao = doubao.normalized(),
        aliyun = aliyun.normalized(),
        acknowledgedProviders = acknowledgedProviders.toSet(),
    )

    fun validate(): VoiceServiceSettings {
        val clean = normalized()
        require(VoiceLanguages.isSupported(clean.provider, clean.language)) {
            "识别语言不在${clean.provider.label}支持范围内"
        }
        when (clean.provider) {
            VoiceProvider.Doubao -> {
                require(clean.doubao.speechApiKey.isNotEmpty()) { "请填写豆包语音 API Key" }
                require(clean.doubao.resourceId.isNotEmpty()) { "请填写豆包语音资源 ID" }
                require(clean.doubao.textApiKey.isNotEmpty()) { "请填写豆包方舟 API Key" }
                require(clean.doubao.textModel.isNotEmpty()) { "请填写方舟模型或接入点 ID" }
                require(clean.doubao.organizerSettings() != null) {
                    "方舟文本服务地址无效；请使用 https 地址（本机调试可用 127.0.0.1）"
                }
            }
            VoiceProvider.Aliyun -> {
                require(clean.aliyun.apiKey.isNotEmpty()) { "请填写阿里云百炼 API Key" }
                require(clean.aliyun.textModel.isNotEmpty()) { "请填写百炼文本模型" }
                require(clean.aliyun.organizerSettings() != null) {
                    "百炼文本服务地址无效；请使用 https 地址（本机调试可用 127.0.0.1）"
                }
            }
        }
        return clean
    }

    fun speechConfigured(): Boolean = when (provider) {
        VoiceProvider.Doubao -> doubao.speechApiKey.isNotBlank() &&
            doubao.resourceId.isNotBlank()
        VoiceProvider.Aliyun -> aliyun.apiKey.isNotBlank()
    }

    fun organizerSettings(): AiSettings? = when (provider) {
        VoiceProvider.Doubao -> doubao.organizerSettings()
        VoiceProvider.Aliyun -> aliyun.organizerSettings()
    }

    fun hasUploadConsent(): Boolean =
        uploadConsentVersion >= UPLOAD_CONSENT_VERSION && provider in acknowledgedProviders

    fun withUploadConsent(): VoiceServiceSettings = copy(
        uploadConsentVersion = UPLOAD_CONSENT_VERSION,
        acknowledgedProviders = acknowledgedProviders + provider,
    )

    fun doubaoSpeechSettings(): DoubaoSpeechSettings? {
        if (provider != VoiceProvider.Doubao || !speechConfigured()) return null
        return doubao.speechSettings(uploadAcknowledged = hasUploadConsent())
    }

    fun withDoubaoResource(resourceId: String): VoiceServiceSettings {
        val clean = resourceId.trim()
        if (clean.isEmpty()) return this
        return copy(doubao = doubao.copy(resourceId = clean))
    }

    override fun toString(): String =
        "VoiceServiceSettings(provider=$provider, language=$language, " +
            "doubao=${doubao.copy(speechApiKey = "***", textApiKey = "***")}, " +
            "aliyun=${aliyun.copy(apiKey = "***")}, " +
            "uploadConsentVersion=$uploadConsentVersion, " +
            "acknowledgedProviders=$acknowledgedProviders)"

    companion object {
        const val UPLOAD_CONSENT_VERSION = 1
    }
}
