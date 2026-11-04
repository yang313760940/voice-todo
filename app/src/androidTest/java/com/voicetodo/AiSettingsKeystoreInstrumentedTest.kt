package com.voicetodo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicetodo.ai.AiSettings
import com.voicetodo.ai.AndroidKeystoreAiSecretCipher
import com.voicetodo.ai.EncryptedAiSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSettingsKeystoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "ai_settings_instrumented"
    private val key = "instrumented" + "-key"

    @Test
    fun androidKeystoreProtectsSettingsAtRest() {
        val cipher = AndroidKeystoreAiSecretCipher()
        val plain = "settings payload".toByteArray()
        val encrypted = cipher.encrypt(plain)
        assertFalse(encrypted.contentEquals(plain))
        assertTrue(cipher.decrypt(encrypted).contentEquals(plain))

        context.deleteSharedPreferences(prefsName)
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val store = EncryptedAiSettingsStore(prefs, cipher)
        val settings = AiSettings(
            baseUrl = "https://api.example.com/v1",
            model = "test-model",
            apiKey = key,
        )
        store.save(settings)

        assertEquals(settings, store.load())
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertFalse("落盘内容不得含明文密钥", raw.contains(key))
        assertFalse("落盘内容不得含明文服务地址", raw.contains(settings.baseUrl))

        store.clear()
        assertNull(store.load())
        context.deleteSharedPreferences(prefsName)
    }
}
