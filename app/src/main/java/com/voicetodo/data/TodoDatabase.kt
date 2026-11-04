package com.voicetodo.data

import androidx.room.*
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.TodoReminder
import com.voicetodo.domain.Quadrant
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "todos")
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
    @Query("SELECT deliveredAt FROM deliveries WHERE itemId = :id AND reminderRevision = :revision")
    suspend fun deliveredAt(id: String, revision: Long): Instant?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun delivered(record: DeliveryEntity)
    @Query("SELECT deliveredAt FROM reminder_deliveries WHERE itemId = :itemId AND reminderId = :reminderId AND revision = :revision")
    suspend fun reminderDeliveredAt(itemId: String, reminderId: String, revision: Long): Instant?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun delivered(record: ReminderDeliveryEntity)
    @Query("SELECT COUNT(*) FROM storage_migrations WHERE name = :name") suspend fun migrated(name: String): Int
    @Insert suspend fun migrated(record: StorageMigration)
}

@Database(entities = [TodoEntity::class, DeliveryEntity::class, ReminderDeliveryEntity::class, StorageMigration::class], version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)], exportSchema = true)
@TypeConverters(TimeConverters::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun todos(): TodoDao
}
