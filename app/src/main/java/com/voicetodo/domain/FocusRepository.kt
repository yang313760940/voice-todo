package com.voicetodo.domain

interface FocusRepository {
    suspend fun activeSession(): FocusSession?
    suspend fun save(session: FocusSession)
    suspend fun saveSettings(settings: FocusSettings)
    suspend fun settings(): FocusSettings
    suspend fun history(): List<FocusSession>
}

class InMemoryFocusRepository : FocusRepository {
    private val sessions = mutableListOf<FocusSession>()
    private var currentSettings = FocusSettings()
    override suspend fun activeSession(): FocusSession? = sessions.lastOrNull { it.isActive() }
    override suspend fun save(session: FocusSession) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions += session
    }
    override suspend fun saveSettings(settings: FocusSettings) { currentSettings = settings }
    override suspend fun settings(): FocusSettings = currentSettings
    override suspend fun history(): List<FocusSession> = sessions.toList()
}
