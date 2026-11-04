package com.voicetodo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.domain.TodoItem
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 单页待办界面。testTag 用于仪器测试稳定定位：
 * - voice_button 录音/结束
 * - title_field 可编辑标题
 * - hour_field / minute_field 手选时间
 * - save_button / cancel_button 保存与取消
 * - item_row_<index>
 */
@Composable
fun TodoScreen(vm: TodoViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    var hourText by remember { mutableStateOf("") }
    var minuteText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("语音提醒", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // 能力状态：不谎称已就绪
        state.capabilities?.let { cap ->
            val parts = buildList {
                add(if (cap.canScheduleExactAlarm) "精确闹钟 ✓" else "精确闹钟 ✗")
                add(if (cap.canPostNotifications) "通知 ✓" else "通知 ✗")
                add(if (cap.canRecordAudio) "麦克风 ✓" else "麦克风 ✗")
            }
            Text("权限：" + parts.joinToString("  "), style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = { vm.onToggleRecord() },
            modifier = Modifier.fillMaxWidth().testTag("voice_button"),
            enabled = state.recordState != RecordState.Transcribing,
        ) {
            Text(
                when (state.recordState) {
                    RecordState.Idle -> "开始录音"
                    RecordState.Recording -> "结束录音"
                    RecordState.Transcribing -> "识别中…"
                }
            )
        }

        OutlinedTextField(
            value = state.draftText,
            onValueChange = { vm.onDraftTextChange(it) },
            label = { Text("识别文字 / 标题（可编辑）") },
            modifier = Modifier.fillMaxWidth().testTag("title_field"),
        )

        // 手选一个未来时间
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hourText,
                onValueChange = { hourText = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("时") },
                modifier = Modifier.weight(1f).testTag("hour_field"),
            )
            OutlinedTextField(
                value = minuteText,
                onValueChange = { minuteText = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("分") },
                modifier = Modifier.weight(1f).testTag("minute_field"),
            )
            Button(
                onClick = {
                    val h = hourText.toIntOrNull() ?: return@Button
                    val m = minuteText.toIntOrNull() ?: return@Button
                    if (h in 0..23 && m in 0..59) vm.onPickTime(h, m)
                },
                modifier = Modifier.testTag("pick_time_button"),
            ) { Text("定时间") }
        }

        Text(
            if (state.draftHasTime) "提醒时间：${state.draftTime?.format(timeFmt)}"
            else "尚未选择提醒时间",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.onSave() }, modifier = Modifier.weight(1f).testTag("save_button")) {
                Text("保存并安排提醒")
            }
            OutlinedButton(onClick = { vm.onCancel() }, modifier = Modifier.weight(1f).testTag("cancel_button")) {
                Text("取消")
            }
        }

        state.message?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("message"))
        }

        HorizontalDivider()

        Text("已保存事项", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.items, key = { it.id }) { item ->
                ItemRow(item, onDelete = { vm.onDelete(item.id) },
                    tag = "item_row_${state.items.indexOf(item)}")
            }
        }
    }
}

@Composable
private fun ItemRow(item: TodoItem, onDelete: () -> Unit, tag: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Medium)
                Text("提醒：${item.remindAt.format(timeFmt)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
    Spacer(Modifier.height(0.dp))
}
