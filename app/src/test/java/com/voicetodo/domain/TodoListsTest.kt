package com.voicetodo.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoListsTest {
    private val clock = FrozenClock(Instant.parse("2026-11-05T01:00:00Z"))

    @Test fun listsCanBeCreatedRenamedAndItemsMoved() = runTest {
        val repo = InMemoryTodoRepository()
        val useCases = TodoUseCases(repo, RecordedScheduler(), clock)
        val work = useCases.createList("  工作  ")
        val life = useCases.createList("生活")
        val item = useCases.confirmDraft("交报告", listId = work.id).item

        assertEquals("工作", work.name)
        assertEquals(setOf(work, life), useCases.listTodoLists().toSet())
        assertEquals(work.id, item.listId)

        useCases.renameList(work.id, "项目")
        assertEquals("项目", useCases.listTodoLists().first { it.id == work.id }.name)

        useCases.moveItemToList(item.id, life.id)
        assertEquals(life.id, useCases.findItem(item.id).listId)
        useCases.moveItemToList(item.id, null)
        assertNull(useCases.findItem(item.id).listId)
    }

    @Test fun deletingAListMovesItemsToInboxWithoutLosingTimesOrCompletion() = runTest {
        val repo = InMemoryTodoRepository()
        val useCases = TodoUseCases(repo, RecordedScheduler(), clock)
        val work = useCases.createList("工作")
        val due = clock.now().plus(Duration.ofDays(2))
        val remind = clock.now().plus(Duration.ofDays(1))
        val pending = useCases.confirmDraft(
            "保留时间", remindAt = remind, plannedDate = java.time.LocalDate.of(2026, 11, 8),
            dueAt = due, listId = work.id,
        ).item
        val completed = useCases.confirmDraft("已完成", listId = work.id).item
        useCases.completeItem(completed.id)

        useCases.deleteList(work.id)

        assertTrue(useCases.listTodoLists().isEmpty())
        val restored = useCases.findItem(pending.id)
        assertNull(restored.listId)
        assertEquals(remind, restored.remindAt)
        assertEquals(due, restored.dueAt)
        assertEquals(java.time.LocalDate.of(2026, 11, 8), restored.plannedDate)
        assertNull(useCases.findItem(completed.id).listId)
        assertTrue(useCases.findItem(completed.id).completedAt != null)
    }

    @Test fun listNamesCannotBeBlankAndMissingListsAreRejected() = runTest {
        val useCases = TodoUseCases(InMemoryTodoRepository(), RecordedScheduler(), clock)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { useCases.createList("   ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCases.confirmDraft("事项", listId = "missing")
            }
        }
    }
}
