package com.voicetodo.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** 朗读呈现策略：成功、锁屏隐私、缺资源回退均由纯函数决定。 */
class ReminderSpeechPolicyTest {
    @Test fun readingOffKeepsExistingToneOnly() {
        assertEquals(
            SpeechDecision.Silent,
            decideReminderSpeech(
                readAloud = false,
                readAloudWhenLocked = true,
                deviceLocked = false,
                availability = SpeechAvailability.Ready,
            ),
        )
    }

    @Test fun unlockedReadingSpeaksWhenLocalResourceIsReady() {
        assertEquals(
            SpeechDecision.Speak,
            decideReminderSpeech(
                readAloud = true,
                readAloudWhenLocked = false,
                deviceLocked = false,
                availability = SpeechAvailability.Ready,
            ),
        )
    }

    @Test fun lockedReadingStaysSilentUnlessExplicitlyAllowed() {
        assertEquals(
            SpeechDecision.Silent,
            decideReminderSpeech(
                readAloud = true,
                readAloudWhenLocked = false,
                deviceLocked = true,
                availability = SpeechAvailability.Ready,
            ),
        )
        assertEquals(
            SpeechDecision.Speak,
            decideReminderSpeech(
                readAloud = true,
                readAloudWhenLocked = true,
                deviceLocked = true,
                availability = SpeechAvailability.Ready,
            ),
        )
    }

    @Test fun missingLocalResourcesFallBackWithReason() {
        val cases = mapOf(
            SpeechAvailability.MissingLanguageData to SpeechFallbackReason.MISSING_DATA,
            SpeechAvailability.UnsupportedLanguage to SpeechFallbackReason.UNSUPPORTED_LANGUAGE,
            SpeechAvailability.EngineUnavailable to SpeechFallbackReason.ENGINE_UNAVAILABLE,
        )
        cases.forEach { (availability, reason) ->
            assertEquals(
                SpeechDecision.Fallback(reason),
                decideReminderSpeech(
                    readAloud = true,
                    readAloudWhenLocked = true,
                    deviceLocked = false,
                    availability = availability,
                ),
            )
        }
    }

    @Test fun lockedPrivacyTakesPrecedenceOverMissingResourceMessage() {
        assertEquals(
            SpeechDecision.Silent,
            decideReminderSpeech(
                readAloud = true,
                readAloudWhenLocked = false,
                deviceLocked = true,
                availability = SpeechAvailability.MissingLanguageData,
            ),
        )
    }
}
