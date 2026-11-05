package com.voicetodo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.voicetodo.domain.FocusPhase
import com.voicetodo.domain.FocusRepository
import com.voicetodo.domain.FocusSession
import com.voicetodo.domain.FocusSessionState
import com.voicetodo.domain.FocusSettings
import java.time.Instant

@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val phase: String,
    val startedAt: Instant,
    val endsAt: Instant,
    val lastResumedAt: Instant,
    val durationSeconds: Long,
    val pausedAt: Instant?,
    val accumulatedFocusSeconds: Long,
    val completedPomodoros: Int,
    val itemId: String?,
    val state: String,
    val endedAt: Instant?,
) {
    fun session() = FocusSession(
        id = id, phase = FocusPhase.valueOf(phase), startedAt = startedAt, endsAt = endsAt,
        lastResumedAt = lastResumedAt, durationSeconds = durationSeconds, pausedAt = pausedAt,
        accumulatedFocusSeconds = accumulatedFocusSeconds, completedPomodoros = completedPomodoros,
        itemId = itemId, state = FocusSessionState.valueOf(state), endedAt = endedAt,
    )

    companion object {
        fun from(session: FocusSession) = FocusSessionEntity(
            id = session.id, phase = session.phase.name, startedAt = session.startedAt,
            endsAt = session.endsAt, lastResumedAt = session.lastResumedAt,
            durationSeconds = session.durationSeconds, pausedAt = session.pausedAt,
            accumulatedFocusSeconds = session.accumulatedFocusSeconds,
            completedPomodoros = session.completedPomodoros, itemId = session.itemId,
            state = session.state.name, endedAt = session.endedAt,
        )
    }
}

@Entity(tableName = "focus_settings")
data class FocusSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val focusMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val longBreakEvery: Int,
) {
    fun settings() = FocusSettings(focusMinutes, shortBreakMinutes, longBreakMinutes, longBreakEvery)

    companion object {
        fun from(settings: FocusSettings) = FocusSettingsEntity(
            focusMinutes = settings.focusMinutes,
            shortBreakMinutes = settings.shortBreakMinutes,
            longBreakMinutes = settings.longBreakMinutes,
            longBreakEvery = settings.longBreakEvery,
        )
    }
}

@Dao
interface FocusDao {
    @Query("SELECT * FROM focus_sessions ORDER BY startedAt, id")
    suspend fun history(): List<FocusSessionEntity>
    @Query("SELECT * FROM focus_sessions WHERE state IN ('Running', 'Paused', 'EndedPendingConfirmation') ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): FocusSessionEntity?
    @Upsert suspend fun save(session: FocusSessionEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun importAll(sessions: List<FocusSessionEntity>)
    @Query("DELETE FROM focus_sessions") suspend fun clearSessions()
    @Query("SELECT * FROM focus_settings WHERE id = 1")
    suspend fun settings(): FocusSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSettings(settings: FocusSettingsEntity)
}

class RoomFocusRepository(private val db: TodoDatabase) : FocusRepository {
    private val dao get() = db.focus()
    override suspend fun activeSession(): FocusSession? = dao.active()?.session()
    override suspend fun save(session: FocusSession) { dao.save(FocusSessionEntity.from(session)) }
    override suspend fun saveSettings(settings: FocusSettings) {
        dao.saveSettings(FocusSettingsEntity.from(settings))
    }
    override suspend fun settings(): FocusSettings = dao.settings()?.settings() ?: FocusSettings()
    override suspend fun history(): List<FocusSession> = dao.history().map { it.session() }
}
