package com.voicetodo.speech

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.ai.AiSecretCipher
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
    private val store = EncryptedSpeechSettingsStore(prefs, ReversibleCipher())
    private val testApiKey = "test" + "-speech-credential"
    private val settings = DoubaoSpeechSettings(
        apiKey = testApiKey,
        resourceId = DoubaoSpeechSettings.LEGACY_RESOURCE_ID,
        uploadAcknowledged = true,
    )

    @Test
    fun `settings round trip without storing the api key in plaintext`() {
        store.save(settings)

        assertEquals(settings, store.load())
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertFalse("SharedPreferences 不应包含明文密钥", raw.contains(testApiKey))
        assertFalse("SharedPreferences 不应包含资源 ID 明文", raw.contains("volc.bigasr"))
    }

    @Test
    fun `clearing settings removes the encrypted payload`() {
        store.save(settings)

        store.clear()

        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `settings text never exposes the api key`() {
        assertFalse(settings.toString().contains(settings.apiKey))
        assertTrue(settings.toString().contains("***"))
    }

    @Test
    fun `blank api key is rejected`() {
        val error = runCatching { DoubaoSpeechSettings(" ").validate() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("API Key"))
    }

    private class ReversibleCipher : AiSecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(envelope: ByteArray): ByteArray =
            envelope.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
