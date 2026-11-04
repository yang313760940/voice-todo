package com.voicetodo.data

import androidx.room.*
import com.voicetodo.domain.TodoItem
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder

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
) {
    fun item() = TodoItem(id, title, remindAt, plannedDate, dueAt, completedAt, createdAt, reminderRevision)
    companion object {
        fun from(item: TodoItem) = TodoEntity(item.id, item.title, item.remindAt, item.plannedDate,
            item.dueAt, item.completedAt, item.createdAt, item.reminderRevision)
    }
}

@Entity(tableName = "deliveries", primaryKeys = ["itemId", "reminderRevision"])
data class DeliveryEntity(val itemId: String, val reminderRevision: Long, val deliveredAt: Instant)

@Entity(tableName = "storage_migrations")
data class StorageMigration(@PrimaryKey val name: String)

class TimeConverters {
    // Fixed precision preserves nanoseconds and makes UTC text ordering chronological.
    private val instantFormat = DateTimeFormatterBuilder().appendInstant(9).toFormatter()
    @TypeConverter fun instant(value: String?): Instant? = value?.let(Instant::parse)
    @TypeConverter fun instantText(value: Instant?): String? = value?.let(instantFormat::format)
    @TypeConverter fun date(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun dateText(value: LocalDate?): String? = value?.toString()
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
    @Query("SELECT COUNT(*) FROM storage_migrations WHERE name = :name") suspend fun migrated(name: String): Int
    @Insert suspend fun migrated(record: StorageMigration)
}

@Database(entities = [TodoEntity::class, DeliveryEntity::class, StorageMigration::class], version = 1,
    exportSchema = true)
@TypeConverters(TimeConverters::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun todos(): TodoDao
}
