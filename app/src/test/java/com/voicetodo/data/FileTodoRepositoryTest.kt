package com.voicetodo.data

import com.voicetodo.domain.TodoItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileTodoRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `all date fields survive reopening a file repository`() = runTest {
        val dir = temp.newFolder("round-trip")
        val item = TodoItem(
            id = "item-1",
            title = "完整日期字段",
            remindAt = Instant.parse("2026-11-05T09:30:00Z"),
            plannedDate = LocalDate.of(2026, 11, 5),
            dueAt = Instant.parse("2026-11-05T12:00:00Z"),
            completedAt = Instant.parse("2026-11-05T10:00:00Z"),
            createdAt = Instant.parse("2026-11-04T08:00:00Z"),
            reminderRevision = 7,
        )
        FileTodoRepository(dir, ZoneId.of("Asia/Shanghai")).save(item)

        val reopened = FileTodoRepository(dir, ZoneId.of("Asia/Shanghai")).all().single()

        assertEquals(item, reopened)
    }

    @Test
    fun `legacy local date times are read using the explicit zone`() = runTest {
        val dir = temp.newFolder("legacy")
        File(dir, "todos.json").writeText(
            """[{"id":"legacy-1","title":"旧事项","remindAt":"2026-11-04T09:00:00","createdAt":"2026-11-04T08:00:00"}]"""
        )

        val item = FileTodoRepository(dir, ZoneId.of("Asia/Shanghai")).all().single()

        assertEquals("legacy-1", item.id)
        assertEquals("旧事项", item.title)
        assertEquals(Instant.parse("2026-11-04T01:00:00Z"), item.remindAt)
        assertEquals(Instant.parse("2026-11-04T00:00:00Z"), item.createdAt)
        assertNull(item.plannedDate)
        assertNull(item.dueAt)
        assertNull(item.completedAt)
        assertEquals(0L, item.reminderRevision)
    }

    @Test
    fun `invalid json is reported and the original file is preserved`() = runTest {
        val dir = temp.newFolder("invalid")
        val file = File(dir, "todos.json")
        val original = """{"broken":"""
        file.writeText(original)

        val failure = runCatching {
            FileTodoRepository(dir, ZoneId.of("Asia/Shanghai")).all()
        }.exceptionOrNull()

        assertTrue("读取失败必须暴露可诊断错误", failure is TodoStorageException)
        assertEquals(original, file.readText())
    }
}
