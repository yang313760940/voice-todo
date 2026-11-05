package com.voicetodo.data

import androidx.room.*
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoList
import com.voicetodo.domain.TodoReminder
import java.time.DayOfWeek
import java.time.Duration
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatterBuilder
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "todo_lists")
data class TodoListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Instant,
) {
    fun list() = TodoList(id, name, createdAt)
    companion object {
        fun from(list: TodoList) = TodoListEntity(list.id, list.name, list.createdAt)
    }
}

@Entity(tableName = "todos", indices = [Index("listId")])
data class TodoEntity(
    @PrimaryKey val id: String,
    val title: String,
    val remindAt: Instant?,
    val plannedDate: LocalDate?,
    val dueAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val reminderRevision: Long,
    @ColumnInfo(defaultValue = "'[]'") val reminders: List<TodoReminder> = emptyList(),
    @ColumnInfo(defaultValue = "'NotImportantNotUrgent'")
    val quadrant: Quadrant = Quadrant.NotImportantNotUrgent,
    /** AI 的初始建议，只用于展示；用户移动象限后仍保留原建议。 */
    val suggestedQuadrant: Quadrant? = null,
    /** 所属自建清单；为空表示收集箱。 */
    val listId: String? = null,
    val recurrenceRule: String? = null,
    val seriesId: String? = null,
    val occurrenceDate: LocalDate? = null,
    /** 该实例是否使用持续响铃通知；只影响提醒呈现，不影响事项本身。 */
    @ColumnInfo(defaultValue = "0") val persistentRing: Boolean = false,
    /** 本次提醒被关闭/延后后的已处理时刻，使旧投递失效。 */
    val dismissedAt: Instant? = null,
) {
    fun item() = TodoItem(
        id = id,
        title = title,
        remindAt = remindAt,
        plannedDate = plannedDate,
        dueAt = dueAt,
        completedAt = completedAt,
        createdAt = createdAt,
        reminderRevision = reminderRevision,
        reminders = reminders,
        quadrant = quadrant,
        suggestedQuadrant = suggestedQuadrant,
        listId = listId,
        seriesId = seriesId,
        occurrenceDate = occurrenceDate,
        recurrence = RecurrenceCodec.decode(recurrenceRule),
        persistentRing = persistentRing,
        dismissedAt = dismissedAt,
    )
    companion object {
        fun from(item: TodoItem) = TodoEntity(
            id = item.id,
            title = item.title,
            remindAt = item.remindAt,
            plannedDate = item.plannedDate,
            dueAt = item.dueAt,
            completedAt = item.completedAt,
            createdAt = item.createdAt,
            reminderRevision = item.reminderRevision,
            reminders = item.reminders,
            quadrant = item.quadrant,
            suggestedQuadrant = item.suggestedQuadrant,
            listId = item.listId,
            recurrenceRule = item.recurrence?.let(RecurrenceCodec::encode),
            seriesId = item.seriesId,
            occurrenceDate = item.occurrenceDate,
            persistentRing = item.persistentRing,
            dismissedAt = item.dismissedAt,
        )
    }
}

internal object RecurrenceCodec {
    fun encode(rule: RecurrenceRule): String = JSONObject().apply {
        put("frequency", rule.frequency.name)
        put("startDate", rule.startDate.toString())
        put("weekdays", JSONArray().apply { rule.weekdays.sorted().forEach { put(it.name) } })
        rule.dayOfMonth?.let { put("dayOfMonth", it) }
        put("reminderTimes", JSONArray().apply { rule.reminderTimes.forEach { put(it.toString()) } })
    }.toString()

    fun decode(text: String?): RecurrenceRule? = text?.let {
        val record = JSONObject(it)
        val weekdays = record.optJSONArray("weekdays") ?: JSONArray()
        val times = record.optJSONArray("reminderTimes") ?: JSONArray()
        RecurrenceRule(
            frequency = RecurrenceFrequency.valueOf(record.getString("frequency")),
            startDate = LocalDate.parse(record.getString("startDate")),
            weekdays = (0 until weekdays.length())
                .map { index -> DayOfWeek.valueOf(weekdays.getString(index)) }
                .toSet(),
            dayOfMonth = record.takeIf { it.has("dayOfMonth") }?.getInt("dayOfMonth"),
            reminderTimes = (0 until times.length()).map { index -> LocalTime.parse(times.getString(index)) },
        )
    }
}

@Entity(tableName = "deliveries", primaryKeys = ["itemId", "reminderRevision"])
data class DeliveryEntity(val itemId: String, val reminderRevision: Long, val deliveredAt: Instant)

@Entity(tableName = "reminder_deliveries", primaryKeys = ["itemId", "reminderId", "revision"],
    foreignKeys = [ForeignKey(entity = TodoEntity::class, parentColumns = ["id"], childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE)], indices = [Index("itemId")])
data class ReminderDeliveryEntity(val itemId: String, val reminderId: String, val revision: Long,
    val deliveredAt: Instant)

@Entity(tableName = "storage_migrations")
data class StorageMigration(@PrimaryKey val name: String)

class TimeConverters {
    // Fixed precision preserves nanoseconds and makes UTC text ordering chronological.
    private val instantFormat = DateTimeFormatterBuilder().appendInstant(9).toFormatter()
    @TypeConverter fun instant(value: String?): Instant? = value?.let(Instant::parse)
    @TypeConverter fun instantText(value: Instant?): String? = value?.let(instantFormat::format)
    @TypeConverter fun date(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun dateText(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun reminderList(value: String): List<TodoReminder> {
        val records = JSONArray(value)
        return (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            TodoReminder(record.getString("id"), Instant.parse(record.getString("at")),
                record.optString("beforeDue").takeIf { it.isNotEmpty() }?.let(Duration::parse),
                record.optLong("revision", 0),
                com.voicetodo.domain.ReminderOutcome.valueOf(record.optString("outcome", "NotReady")),
                record.optString("deliveredAt").takeIf { it.isNotEmpty() }?.let(Instant::parse))
        }
    }
    @TypeConverter fun reminderText(value: List<TodoReminder>): String = JSONArray().apply {
        value.forEach { reminder ->
            put(JSONObject().apply {
                put("id", reminder.id)
                put("at", reminder.at.toString())
                reminder.beforeDue?.let { put("beforeDue", it.toString()) }
                put("revision", reminder.revision)
                put("outcome", reminder.outcome.name)
                reminder.deliveredAt?.let { put("deliveredAt", it.toString()) }
            })
        }
    }.toString()
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos ORDER BY id") suspend fun all(): List<TodoEntity>
    @Query("SELECT * FROM todos WHERE completedAt IS NULL ORDER BY remindAt IS NULL, remindAt ASC, createdAt DESC, id ASC")
    suspend fun incomplete(): List<TodoEntity>
    @Query("SELECT * FROM todos WHERE completedAt IS NOT NULL ORDER BY completedAt DESC, id ASC")
    suspend fun completed(): List<TodoEntity>
    @Query("SELECT * FROM todos WHERE id = :id") suspend fun find(id: String): TodoEntity?
    @Upsert suspend fun save(item: TodoEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun importItems(items: List<TodoEntity>)
    @Query("DELETE FROM todos WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM deliveries WHERE itemId = :id") suspend fun deleteDeliveries(id: String)
    @Query("SELECT * FROM todo_lists ORDER BY createdAt ASC, id ASC") suspend fun lists(): List<TodoListEntity>
    @Query("SELECT * FROM todo_lists WHERE id = :id") suspend fun findList(id: String): TodoListEntity?
    @Upsert suspend fun saveList(list: TodoListEntity)
    @Query("DELETE FROM todo_lists WHERE id = :id") suspend fun deleteList(id: String)
    @Query("UPDATE todos SET listId = NULL WHERE listId = :id") suspend fun clearList(id: String)
    @Query("SELECT deliveredAt FROM deliveries WHERE itemId = :id AND reminderRevision = :revision")
    suspend fun deliveredAt(id: String, revision: Long): Instant?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun delivered(record: DeliveryEntity)
    @Query("SELECT deliveredAt FROM reminder_deliveries WHERE itemId = :itemId AND reminderId = :reminderId AND revision = :revision")
    suspend fun reminderDeliveredAt(itemId: String, reminderId: String, revision: Long): Instant?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun delivered(record: ReminderDeliveryEntity)
    @Query("SELECT COUNT(*) FROM storage_migrations WHERE name = :name") suspend fun migrated(name: String): Int
    @Insert suspend fun migrated(record: StorageMigration)
}

@Database(
    entities = [
        TodoEntity::class,
        DeliveryEntity::class,
        ReminderDeliveryEntity::class,
        StorageMigration::class,
        FocusSessionEntity::class,
        FocusSettingsEntity::class,
        TodoListEntity::class,
    ],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true,
)
@TypeConverters(TimeConverters::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun todos(): TodoDao
    abstract fun focus(): FocusDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `focus_sessions` (`id` TEXT NOT NULL, `phase` TEXT NOT NULL, `startedAt` TEXT NOT NULL, `endsAt` TEXT NOT NULL, `lastResumedAt` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `pausedAt` TEXT, `accumulatedFocusSeconds` INTEGER NOT NULL, `completedPomodoros` INTEGER NOT NULL, `itemId` TEXT, `state` TEXT NOT NULL, `endedAt` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `focus_settings` (`id` INTEGER NOT NULL, `focusMinutes` INTEGER NOT NULL, `shortBreakMinutes` INTEGER NOT NULL, `longBreakMinutes` INTEGER NOT NULL, `longBreakEvery` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO focus_settings (id, focusMinutes, shortBreakMinutes, longBreakMinutes, longBreakEvery) VALUES (1, 25, 5, 15, 4)")
            }
        }

        /**
         * v5 -> v6：新增自建清单与事项归属。
         *
         * 不重建 todos 表，避免删除父表时把 reminder_deliveries 一并级联删除；
         * 清单删除由仓库事务先把事项移回收集箱。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `todo_lists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("ALTER TABLE `todos` ADD COLUMN `listId` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_listId` ON `todos` (`listId`)")
            }
        }
    }
}
