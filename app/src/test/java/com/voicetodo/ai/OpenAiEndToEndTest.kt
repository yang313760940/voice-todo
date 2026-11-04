package com.voicetodo.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicetodo.data.FileTodoRepository
import com.voicetodo.data.RoomTodoRepository
import com.voicetodo.data.TodoDatabase
import com.voicetodo.domain.FrozenClock
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.RecordedScheduler
import com.voicetodo.domain.TodoUseCases
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用真实本地 HTTP 服务验证：加密配置读取 → 实际请求 → 草稿 → 用户确认 → Room + 系统调度。
 * 服务是测试桩，只证明客户端契约与闭环，不冒充真实供应商验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OpenAiEndToEndTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var db: TodoDatabase
    private val clock = FrozenClock(Instant.parse("2026-11-04T09:00:00Z"))
    private val zone = ZoneId.of("Asia/Shanghai")
    private val scheduler = RecordedScheduler()
    private val key = "test" + "-key"

    @After fun teardown() {
        if (this::db.isInitialized) db.close()
    }

    @Test
    fun `configured service creates a draft and confirmation persists and schedules it`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, TodoDatabase::class.java).build()
        val repo = RoomTodoRepository(
            db,
            FileTodoRepository(temp.newFolder(), zone),
        )
        val useCases = TodoUseCases(repo, scheduler, clock)
        val store = MemoryAiSettingsStore()
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put(
                            "content",
                            JSONObject()
                                .put("title", "提交季度报告")
                                .put("plannedDate", "2026-11-05")
                                .put("dueAt", "2026-11-06 18:00")
                                .put(
                                    "reminders",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("kind", "SPECIFIED")
                                                .put("at", "2026-11-05 18:15"),
                                        )
                                        .put(
                                            JSONObject()
                                                .put("kind", "RELATIVE")
                                                .put("minutesBeforeDue", 60),
                                        ),
                                )
                                .put("quadrant", "IMPORTANT_URGENT")
                                .toString(),
                        ),
                    ),
                ),
            )
            .toString()
        LocalHttpServer(200, response).use { server ->
            store.save(AiSettings("http://127.0.0.1:${server.port}/v1", "test-model", key))
            val configured = store.load()!!
            runBlocking { useCases.confirmDraft(title = "别的私事") }
            val schedulesBefore = scheduler.scheduled.size

            val draft = runBlocking {
                OpenAiCompatibleClient().organize(
                    "明天下午交季度报告",
                    AiPromptContext(clock.now(), zone),
                    configured,
                )
            }

            val requestBody = server.requests.single().body
            assertTrue(requestBody.contains("明天下午交季度报告"))
            assertFalse("不得发送其他事项", requestBody.contains("别的私事"))
            assertEquals(1, runBlocking { repo.all().size })
            assertEquals("确认前不得新增调度", schedulesBefore, scheduler.scheduled.size)

            val edit = draft.toTodoEdit(zone)
            val result = runBlocking {
                useCases.confirmDraft(
                    title = edit.title,
                    plannedDate = edit.plannedDate,
                    dueAt = edit.dueAt,
                    reminders = edit.reminders.orEmpty(),
                    quadrant = draft.quadrant,
                    itemId = draft.itemId,
                )
            }

            assertEquals(2, runBlocking { repo.all().size })
            val saved = result.item
            assertEquals("提交季度报告", saved.title)
            assertEquals(Quadrant.ImportantUrgent, saved.quadrant)
            assertEquals(2, saved.reminders.size)
            assertEquals(schedulesBefore + 2, scheduler.scheduled.size)
        }
    }
}
