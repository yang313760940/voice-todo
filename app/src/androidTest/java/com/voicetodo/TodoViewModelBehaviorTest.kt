package com.voicetodo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetodo.ui.TodoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 用户操作层行为测试（真实 ViewModel + 真实持久化 + 真实系统闹钟调度，
 * 在应用进程内 on-device 运行）。
 *
 * 规避 API 37 上 Espresso 输入注入不可用的问题（android.hardware.input.InputManager
 * .getInstance 已被移除），直接驱动用户的同一操作序列：
 *  输入标题 → 手选时间 → 保存 / 取消。
 */
@RunWith(AndroidJUnit4::class)
class TodoViewModelBehaviorTest {

    private val app = ApplicationProvider.getApplicationContext<VoiceTodoApp>()

    private fun freshViewModel(): TodoViewModel = TodoViewModel(app)

    @Test
    fun savePersistsItem_andCancelCreatesNothing() {
        val vm = freshViewModel()
        val before = vm.state.value.items.size
        val saveTitle = "行为测试-保存-${System.currentTimeMillis()}"
        val cancelTitle = "行为测试-取消-${System.currentTimeMillis()}"

        // ---- 保存路径：输入标题 + 手选一个未来时刻 + 保存 ----
        val future = LocalDateTime.now().plusHours(1)
        vm.onDraftTextChange(saveTitle)
        vm.onPickTime(future.hour, future.minute)
        assertNotNull("手选后应记录提醒时间", vm.state.value.draftTime)
        vm.onSave()

        val afterSave = vm.state.value.items
        assertEquals("保存后应新增一条", before + 1, afterSave.size)
        assertTrue("列表应包含刚保存的事项", afterSave.any { it.title == saveTitle })

        // ---- 取消路径：输入标题后取消，不创建 ----
        vm.onDraftTextChange(cancelTitle)
        vm.onCancel()
        val afterCancel = vm.state.value.items
        assertFalse("取消后不应出现该事项", afterCancel.any { it.title == cancelTitle })
        assertEquals("取消不改变已有数量", before + 1, afterCancel.size)

        // 清理本次测试产生的事项
        afterSave.filter { it.title == saveTitle }.forEach { vm.onDelete(it.id) }
    }

    @Test
    fun emptyTitleIsRejected() {
        val vm = freshViewModel()
        val before = vm.state.value.items.size
        vm.onDraftTextChange("   ")
        vm.onPickTime(LocalTime.now().plusMinutes(30).hour, LocalTime.now().plusMinutes(30).minute)
        vm.onSave()
        assertEquals("空标题不应创建事项", before, vm.state.value.items.size)
    }
}
