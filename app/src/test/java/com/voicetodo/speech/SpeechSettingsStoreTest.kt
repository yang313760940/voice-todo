package com.voicetodo.speech

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.ai.AiSecretCipher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SpeechSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = context.getSharedPreferences(
        "speech_settings_test_${System.nanoTime()}",
        Context.MODE_PRIVATE,
    )
    private val cipher = ReversibleCipher()
    private val store = EncryptedSpeechSettingsStore(prefs, cipher)
    private val doubaoKey = "test" + "-doubao-speech-credential"
    private val doubaoTextKey = "test" + "-doubao-ark-credential"
    private val aliyunKey = "test" + "-aliyun-credential"
    private val settings = VoiceServiceSettings(
        provider = VoiceProvider.Doubao,
        language = VoiceLanguages.Cantonese.code,
        doubao = DoubaoVoiceCredentials(
            speechApiKey = doubaoKey,
            resourceId = DoubaoSpeechSettings.LEGACY_RESOURCE_ID,
            textApiKey = doubaoTextKey,
            textModel = "ep-test",
        ),
        aliyun = AliyunVoiceCredentials(apiKey = aliyunKey),
        uploadConsentVersion = VoiceServiceSettings.UPLOAD_CONSENT_VERSION,
        acknowledgedProviders = setOf(VoiceProvider.Doubao),
    )

    @Test
    fun `settings round trip without storing credentials in plaintext`() {
        store.save(settings)

        assertEquals(settings, store.load())
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        listOf(doubaoKey, doubaoTextKey, aliyunKey, "volc.bigasr").forEach { secret ->
            assertFalse("SharedPreferences 不应包含明文：$secret", raw.contains(secret))
        }
    }

    @Test
    fun `legacy doubao payload is migrated without losing consent`() {
        val legacy = JSONObject()
            .put("apiKey", doubaoKey)
            .put("resourceId", DoubaoSpeechSettings.LEGACY_RESOURCE_ID)
            .put("uploadAcknowledged", true)
            .toString()
        val encrypted = cipher.encrypt(legacy.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(
                EncryptedSpeechSettingsStore.KEY,
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
            .commit()

        val restored = store.load()
        assertEquals(VoiceProvider.Doubao, restored!!.provider)
        assertEquals(doubaoKey, restored.doubao.speechApiKey)
        assertEquals(DoubaoSpeechSettings.LEGACY_RESOURCE_ID, restored.doubao.resourceId)
        assertTrue(restored.hasUploadConsent())
        assertNull("旧配置没有文本凭据，不能假装已可整理", restored.organizerSettings())
    }

    @Test
    fun `clearing settings removes the encrypted payload`() {
        store.save(settings)

        store.clear()

        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `settings text never exposes credentials`() {
        val text = settings.toString()
        assertFalse(text.contains(doubaoKey))
        assertFalse(text.contains(doubaoTextKey))
        assertFalse(text.contains(aliyunKey))
        assertTrue(text.contains("***"))
    }

    @Test
    fun `blank provider credentials are rejected`() {
        val doubaoError = runCatching {
            VoiceServiceSettings(
                provider = VoiceProvider.Doubao,
                doubao = DoubaoVoiceCredentials(textApiKey = "ark", textModel = "ep"),
            ).validate()
        }.exceptionOrNull()
        assertTrue(doubaoError is IllegalArgumentException)
        assertTrue(doubaoError!!.message!!.contains("豆包语音 API Key"))

        val aliyunError = runCatching {
            VoiceServiceSettings(provider = VoiceProvider.Aliyun).validate()
        }.exceptionOrNull()
        assertTrue(aliyunError is IllegalArgumentException)
        assertTrue(aliyunError!!.message!!.contains("百炼 API Key"))
    }

    @Test
    fun `unsupported language is rejected`() {
        val error = runCatching {
            settings.copy(language = "fr-FR").validate()
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("识别语言"))
    }

    private class ReversibleCipher : AiSecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(envelope: ByteArray): ByteArray =
            envelope.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
