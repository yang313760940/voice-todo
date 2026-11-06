package com.voicetodo.speech

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.voicetodo.ai.AiSecretCipher
import com.voicetodo.ai.AndroidKeystoreAiSecretCipher
import org.json.JSONObject

interface SpeechSettingsStore {
    fun load(): VoiceServiceSettings?
    fun save(settings: VoiceServiceSettings)
    fun clear()
}

/**
 * 与 AI 配置相同的设备加密策略：整份 JSON 用 Android Keystore 中的
 * 不可导出 AES-GCM 密钥加密后写入普通 SharedPreferences。
 */
class EncryptedSpeechSettingsStore(
    private val prefs: SharedPreferences,
    private val cipher: AiSecretCipher,
) : SpeechSettingsStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        AndroidKeystoreAiSecretCipher(),
    )

    @Synchronized
    override fun load(): VoiceServiceSettings? {
        val encoded = prefs.getString(KEY, null) ?: return null
        return try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val json = JSONObject(String(cipher.decrypt(envelope), Charsets.UTF_8))
            if (json.has("provider")) parseCurrent(json) else parseLegacy(json)
        } catch (e: Exception) {
            prefs.edit().remove(KEY).apply()
            null
        }
    }

    @Synchronized
    override fun save(settings: VoiceServiceSettings) {
        val clean = settings.validate()
        val json = JSONObject()
            .put("schema", 2)
            .put("provider", clean.provider.name)
            .put("language", clean.language)
            .put(
                "doubao",
                JSONObject()
                    .put("speechApiKey", clean.doubao.speechApiKey)
                    .put("resourceId", clean.doubao.resourceId)
                    .put("textApiKey", clean.doubao.textApiKey)
                    .put("textModel", clean.doubao.textModel)
                    .put("textBaseUrl", clean.doubao.textBaseUrl),
            )
            .put(
                "aliyun",
                JSONObject()
                    .put("apiKey", clean.aliyun.apiKey)
                    .put("textModel", clean.aliyun.textModel)
                    .put("textBaseUrl", clean.aliyun.textBaseUrl)
                    .put("speechModel", clean.aliyun.speechModel),
            )
            .put("uploadConsentVersion", clean.uploadConsentVersion)
            .put(
                "acknowledgedProviders",
                org.json.JSONArray(clean.acknowledgedProviders.map { it.name }),
            )
            .toString()
        val encrypted = cipher.encrypt(json.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun parseCurrent(json: JSONObject): VoiceServiceSettings {
        val provider = VoiceProvider.valueOf(json.getString("provider"))
        val doubao = json.optJSONObject("doubao")
        val aliyun = json.optJSONObject("aliyun")
        val acknowledged = json.optJSONArray("acknowledgedProviders")
        val providers = if (acknowledged == null) {
            emptySet()
        } else {
            (0 until acknowledged.length()).mapNotNull { index ->
                runCatching { VoiceProvider.valueOf(acknowledged.getString(index)) }.getOrNull()
            }.toSet()
        }
        return VoiceServiceSettings(
            provider = provider,
            language = json.optString("language", VoiceLanguages.Mandarin.code),
            doubao = DoubaoVoiceCredentials(
                speechApiKey = doubao?.optString("speechApiKey").orEmpty(),
                resourceId = doubao?.optString(
                    "resourceId",
                    DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
                ).orEmpty().ifBlank { DoubaoSpeechSettings.DEFAULT_RESOURCE_ID },
                textApiKey = doubao?.optString("textApiKey").orEmpty(),
                textModel = doubao?.optString("textModel").orEmpty(),
                textBaseUrl = doubao?.optString(
                    "textBaseUrl",
                    DoubaoVoiceCredentials.ARK_BASE_URL,
                ).orEmpty().ifBlank { DoubaoVoiceCredentials.ARK_BASE_URL },
            ),
            aliyun = AliyunVoiceCredentials(
                apiKey = aliyun?.optString("apiKey").orEmpty(),
                textModel = aliyun?.optString(
                    "textModel",
                    AliyunVoiceCredentials.DEFAULT_TEXT_MODEL,
                ).orEmpty().ifBlank { AliyunVoiceCredentials.DEFAULT_TEXT_MODEL },
                textBaseUrl = aliyun?.optString(
                    "textBaseUrl",
                    AliyunVoiceCredentials.DASHSCOPE_BASE_URL,
                ).orEmpty().ifBlank { AliyunVoiceCredentials.DASHSCOPE_BASE_URL },
                speechModel = aliyun?.optString(
                    "speechModel",
                    AliyunVoiceCredentials.DEFAULT_SPEECH_MODEL,
                ).orEmpty().ifBlank { AliyunVoiceCredentials.DEFAULT_SPEECH_MODEL },
            ),
            uploadConsentVersion = json.optInt("uploadConsentVersion", 0),
            acknowledgedProviders = providers,
        )
    }

    /** 旧版只保存豆包语音凭据；读取后保留为豆包配置，文本凭据留空等待补填。 */
    private fun parseLegacy(json: JSONObject): VoiceServiceSettings {
        val acknowledged = json.optBoolean("uploadAcknowledged", false)
        return VoiceServiceSettings(
            provider = VoiceProvider.Doubao,
            doubao = DoubaoVoiceCredentials(
                speechApiKey = json.optString("apiKey").orEmpty(),
                resourceId = json.optString(
                    "resourceId",
                    DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
                ).orEmpty().ifBlank { DoubaoSpeechSettings.DEFAULT_RESOURCE_ID },
            ),
            uploadConsentVersion = if (acknowledged) VoiceServiceSettings.UPLOAD_CONSENT_VERSION else 0,
            acknowledgedProviders = if (acknowledged) setOf(VoiceProvider.Doubao) else emptySet(),
        )
    }

    companion object {
        const val PREFS_NAME = "speech_settings"
        const val KEY = "encrypted_speech_settings_payload"
    }
}
