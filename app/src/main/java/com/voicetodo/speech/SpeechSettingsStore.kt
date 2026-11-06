package com.voicetodo.speech

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.voicetodo.ai.AiSecretCipher
import com.voicetodo.ai.AndroidKeystoreAiSecretCipher
import org.json.JSONObject

interface SpeechSettingsStore {
    fun load(): DoubaoSpeechSettings?
    fun save(settings: DoubaoSpeechSettings)
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
    override fun load(): DoubaoSpeechSettings? {
        val encoded = prefs.getString(KEY, null) ?: return null
        return try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val json = JSONObject(String(cipher.decrypt(envelope), Charsets.UTF_8))
            DoubaoSpeechSettings(
                apiKey = json.getString("apiKey"),
                resourceId = json.optString(
                    "resourceId",
                    DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
                ),
                uploadAcknowledged = json.optBoolean("uploadAcknowledged", false),
            )
        } catch (e: Exception) {
            prefs.edit().remove(KEY).apply()
            null
        }
    }

    @Synchronized
    override fun save(settings: DoubaoSpeechSettings) {
        val clean = settings.validate()
        val json = JSONObject()
            .put("apiKey", clean.apiKey)
            .put("resourceId", clean.resourceId)
            .put("uploadAcknowledged", clean.uploadAcknowledged)
            .toString()
        val encrypted = cipher.encrypt(json.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val PREFS_NAME = "speech_settings"
        const val KEY = "encrypted_speech_settings_payload"
    }
}
