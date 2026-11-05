package com.voicetodo.ai

import kotlinx.coroutines.CompletableDeferred

class MemoryAiSettingsStore(var settings: AiSettings? = null) : AiSettingsStore {
    override fun load(): AiSettings? = settings
    override fun save(settings: AiSettings) {
        this.settings = settings.validate()
    }
    override fun clear() {
        settings = null
    }
}

class FakeAiAssistClient(
    var result: AiDraft? = null,
    var results: List<AiDraft>? = null,
    var failure: Exception? = null,
) : AiAssistClient {
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    val receivedTexts = mutableListOf<String>()
    val receivedContexts = mutableListOf<AiPromptContext>()

    override suspend fun organize(
        text: String,
        context: AiPromptContext,
        settings: AiSettings,
    ): AiDraft = organizeDrafts(text, context, settings).single()

    override suspend fun organizeDrafts(
        text: String,
        context: AiPromptContext,
        settings: AiSettings,
    ): List<AiDraft> {
        calls++
        receivedTexts += text
        receivedContexts += context
        gate?.await()
        failure?.let { throw it }
        return results ?: listOf(requireNotNull(result) { "测试未提供 AI 结果" })
    }
}
