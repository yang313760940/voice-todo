package com.voicetodo.speech

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MemorySpeechSettingsStore(var settings: DoubaoSpeechSettings? = null) : SpeechSettingsStore {
    override fun load(): DoubaoSpeechSettings? = settings

    override fun save(settings: DoubaoSpeechSettings) {
        this.settings = settings.validate()
    }

    override fun clear() {
        settings = null
    }
}

class FakeSpeechStream : SpeechStream {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
    override val events: Flow<SpeechEvent> = channel.receiveAsFlow()

    var started = false
    var finished = false
    var cancelled = false
    val chunks = mutableListOf<ByteArray>()

    fun emit(event: SpeechEvent) {
        channel.trySend(event)
    }

    override fun start(language: String) {
        started = true
    }

    override fun pushPcm(pcm: ByteArray, length: Int) {
        chunks += pcm.copyOf(length)
    }

    override fun finish() {
        finished = true
    }

    override fun cancel() {
        cancelled = true
        channel.close()
    }
}

class FakeSpeechStreamFactory(
    val stream: FakeSpeechStream = FakeSpeechStream(),
) : SpeechStreamFactory {
    var openedWith: DoubaoSpeechSettings? = null
    var openCalls = 0

    override fun open(settings: DoubaoSpeechSettings): SpeechStream {
        openCalls++
        openedWith = settings
        return stream
    }
}

class FakeSpeechRecorder : SpeechRecorder {
    override val sampleRate = 16000

    var started = false
    var stopped = false
    var cancelled = false
    var released = false
    private var onChunk: ((ByteArray) -> Unit)? = null

    override fun start(onChunk: (ByteArray) -> Unit): Boolean {
        started = true
        this.onChunk = onChunk
        return true
    }

    fun emit(pcm: ByteArray) {
        onChunk?.invoke(pcm)
    }

    override fun stop() {
        stopped = true
    }

    override fun cancel() {
        cancelled = true
    }

    override fun release() {
        released = true
    }
}

class FakeSpeechRecorderFactory(
    val recorder: FakeSpeechRecorder = FakeSpeechRecorder(),
) : SpeechRecorderFactory {
    override fun create(): SpeechRecorder = recorder
}
