package com.voicetodo.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

interface AiSettingsStore {
    fun load(): AiSettings?
    fun save(settings: AiSettings)
    fun clear()
}

/** 设备保护的对称加密接缝；测试可注入可逆替身。 */
interface AiSecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray): ByteArray
}

/**
 * 使用 Android Keystore 中不可导出的 AES-GCM 密钥加密整份配置。
 * 密钥没有硬编码，也不进入 SharedPreferences 或日志。
 */
class AndroidKeystoreAiSecretCipher : AiSecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + encrypted
    }

    override fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size > 2 && envelope[0] == VERSION) { "不支持的密钥存储格式" }
        val ivSize = envelope[1].toInt() and 0xFF
        require(ivSize in 12..16 && envelope.size > 2 + ivSize) { "加密配置已损坏" }
        val iv = envelope.copyOfRange(2, 2 + ivSize)
        val payload = envelope.copyOfRange(2 + ivSize, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(payload)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "voice_todo_ai_settings_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}

/**
 * 配置整体加密后写入普通 SharedPreferences；即使备份或应用私有文件被读取，
 * 没有设备 Keystore 密钥也无法还原 API Key。
 */
class EncryptedAiSettingsStore(
    private val prefs: SharedPreferences,
    private val cipher: AiSecretCipher,
) : AiSettingsStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        AndroidKeystoreAiSecretCipher(),
    )

    @Synchronized
    override fun load(): AiSettings? {
        val encoded = prefs.getString(KEY, null) ?: return null
        return try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val json = JSONObject(String(cipher.decrypt(envelope), Charsets.UTF_8))
            AiSettings(
                baseUrl = json.getString("baseUrl"),
                model = json.getString("model"),
                apiKey = json.getString("apiKey"),
            )
        } catch (e: Exception) {
            // 设备密钥被清除或数据损坏时，丢弃不可用配置，让用户重新填写。
            prefs.edit().remove(KEY).apply()
            null
        }
    }

    @Synchronized
    override fun save(settings: AiSettings) {
        val clean = settings.validate()
        val json = JSONObject()
            .put("baseUrl", clean.baseUrl)
            .put("model", clean.model)
            .put("apiKey", clean.apiKey)
            .toString()
        val encrypted = cipher.encrypt(json.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val PREFS_NAME = "ai_settings"
        const val KEY = "encrypted_settings_payload"
    }
}
