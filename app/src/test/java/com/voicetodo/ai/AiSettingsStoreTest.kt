package com.voicetodo.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AiSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = context.getSharedPreferences(
        "ai_settings_test_${System.nanoTime()}",
        Context.MODE_PRIVATE,
    )
    private val store = EncryptedAiSettingsStore(prefs, ReversibleTestCipher())
    private val testApiKey = "test" + "-credential"
    private val settings = AiSettings(
        baseUrl = "https://api.example.com/v1",
        model = "example-model",
        apiKey = testApiKey,
    )

    @Test
    fun `settings round trip without storing the api key in plaintext`() {
        store.save(settings)

        assertEquals(settings, store.load())
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertFalse("SharedPreferences 不应包含明文密钥", raw.contains(testApiKey))
        assertFalse("SharedPreferences 不应包含完整配置", raw.contains("api.example.com"))
    }

    @Test
    fun `clearing settings removes the encrypted payload`() {
        store.save(settings)

        store.clear()

        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `corrupt ciphertext is discarded instead of reported as configured`() {
        prefs.edit().putString(EncryptedAiSettingsStore.KEY, "AAAA").commit()

        assertNull(store.load())
        assertFalse(prefs.contains(EncryptedAiSettingsStore.KEY))
    }

    @Test
    fun `settings text never exposes the api key`() {
        assertFalse(settings.toString().contains(settings.apiKey))
        assertTrue(settings.toString().contains("***"))
    }

    @Test
    fun `remote plaintext http is rejected but loopback debug url is allowed`() {
        try {
            AiSettings("http://api.example.com/v1", "m", "k").validate()
            fail("远程 http 必须被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }

        val local = AiSettings("http://127.0.0.1:8080/v1", "m", "k").validate()
        assertEquals("http://127.0.0.1:8080/v1", local.baseUrl)
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            local.endpoint().toString(),
        )
    }

    private class ReversibleTestCipher : AiSecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(envelope: ByteArray): ByteArray =
            envelope.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
