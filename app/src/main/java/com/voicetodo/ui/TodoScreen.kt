package com.voicetodo.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.isOverdue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun TodoScreen(vm: TodoViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onRefresh()
        onPauseOrDispose { }
    }
    BackHandler(enabled = state.detail != null) { vm.onCancelDetail() }
    val enabled = !state.loading && !state.busy && state.storageError == null
    val detail = state.detail
    if (detail != null) {
        DetailScreen(vm, state, detail, enabled)
        return
    }
    var hourText by remember { mutableStateOf("") }
    var minuteText by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("todo_list"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("语音提醒", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            state.capabilities?.let { cap ->
                val parts = listOf(
                    if (cap.canScheduleExactAlarm) "精确闹钟 ✓" else "精确闹钟 ✗",
                    if (cap.canPostNotifications) "通知 ✓" else "通知 ✗",
                    if (cap.canRecordAudio) "麦克风 ✓" else "麦克风 ✗",
                )
                Text("权限：" + parts.joinToString("  "), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { StorageStatus(state, vm::onRetryStorage) }
        item {
            Button(onClick = vm::onToggleRecord, modifier = Modifier.fillMaxWidth().testTag("voice_button"),
                enabled = enabled && state.recordState != RecordState.Transcribing) {
                Text(when (state.recordState) {
                    RecordState.Idle -> "开始录音"
                    RecordState.Recording -> "结束录音"
                    RecordState.Transcribing -> "识别中…"
                })
            }
        }
        item {
            OutlinedTextField(value = state.draftText, onValueChange = vm::onDraftTextChange,
                label = { Text("识别文字 / 标题（可编辑）") }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("title_field"))
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = hourText, onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                    label = { Text("时") }, enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("hour_field"))
                OutlinedTextField(value = minuteText, onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                    label = { Text("分") }, enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("minute_field"))
                Button(onClick = {
                    val h = hourText.toIntOrNull()
                    val m = minuteText.toIntOrNull()
                    if (h != null && m != null && h in 0..23 && m in 0..59) vm.onPickTime(h, m)
                }, enabled = enabled, modifier = Modifier.testTag("pick_time_button")) { Text("定时间") }
            }
            Text(state.draftTime?.let { "提醒时间：${it.atZone(vm.zoneId).format(timeFmt)}" }
                ?: "尚未选择提醒时间", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = vm::onSave, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("save_button")) { Text("保存并安排提醒") }
            OutlinedButton(onClick = vm::onSaveWithoutReminder, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("save_without_reminder_button")) { Text("不设提醒直接保存") }
            TextButton(onClick = vm::onCancel, enabled = enabled,
                modifier = Modifier.testTag("cancel_button")) { Text("取消草稿") }
        }
        item { Message(state.message); HorizontalDivider(); Text("待办", style = MaterialTheme.typography.titleMedium) }
        if (state.items.isEmpty()) item { Text("暂无待办", style = MaterialTheme.typography.bodySmall) }
        itemsIndexed(state.items, key = { _, item -> "pending-${item.id}" }) { index, item ->
            ItemRow(item, state, vm.zoneId, "item_row_$index", enabled,
                onOpen = { vm.onOpenDetail(item.id) }, onAction = { vm.onComplete(item.id) },
                onDelete = { vm.onDelete(item.id) }, onRetry = { vm.onRetryReminders(item.id) })
        }
        item { HorizontalDivider(); Text("已完成", style = MaterialTheme.typography.titleMedium) }
        if (state.completedItems.isEmpty()) item { Text("暂无已完成事项", style = MaterialTheme.typography.bodySmall) }
        itemsIndexed(state.completedItems, key = { _, item -> "completed-${item.id}" }) { index, item ->
            ItemRow(item, state, vm.zoneId, "completed_row_$index", enabled,
                onOpen = { vm.onOpenDetail(item.id) }, onAction = { vm.onRestore(item.id) },
                onDelete = { vm.onDelete(item.id) }, onRetry = { })
        }
    }
}

@Composable
private fun DetailScreen(vm: TodoViewModel, state: UiState, detail: DetailDraft, enabled: Boolean) {
    val context = LocalContext.current
    val editable = enabled && detail.original.completedAt == null
    fun pick(field: DetailField, text: String) {
        val initial = runCatching { LocalDate.parse(text.take(10)) }.getOrElse { state.now.atZone(vm.zoneId).toLocalDate() }
        DatePickerDialog(context, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            if (field == DetailField.Plan) vm.onDetailValueChange(field, date.toString())
            else {
                val time = runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toLocalTime() }
                    .getOrElse { LocalTime.of(9, 0) }
                TimePickerDialog(context, { _, hour, minute ->
                    vm.onDetailValueChange(field, LocalDateTime.of(date, LocalTime.of(hour, minute))
                        .format(timeFmt))
                }, time.hour, time.minute, true).show()
            }
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("detail_screen"),
        contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("事项详情", style = MaterialTheme.typography.headlineSmall); StorageStatus(state, vm::onRetryStorage) }
        item {
            OutlinedTextField(value = detail.title, onValueChange = vm::onDetailTitleChange,
                label = { Text("标题") }, enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag("detail_title_field"))
        }
        item {
            DateField("计划日期", "yyyy-MM-dd", detail.plannedDate, "plannedDate", editable,
                onChange = { vm.onDetailValueChange(DetailField.Plan, it) },
                onPick = { pick(DetailField.Plan, detail.plannedDate) })
        }
        item {
            DateField("截止时间", "yyyy-MM-dd HH:mm", detail.dueAt, "dueAt", editable,
                onChange = { vm.onDetailValueChange(DetailField.Due, it) },
                onPick = { pick(DetailField.Due, detail.dueAt) })
        }
        item { Text("提醒安排", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(detail.reminders, key = { _, reminder -> reminder.id }) { index, reminder ->
            Card(Modifier.fillMaxWidth().testTag("detail_reminder_$index")) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (reminder.kind == ReminderDraftKind.Specified) "指定提醒 ${index + 1}"
                        else "相对提醒 ${index + 1}", fontWeight = FontWeight.Medium)
                    if (reminder.kind == ReminderDraftKind.Specified) {
                        OutlinedTextField(reminder.value, { vm.onReminderValueChange(index, it) },
                            enabled = editable, singleLine = true, label = { Text("提醒时间") },
                            placeholder = { Text("yyyy-MM-dd HH:mm") },
                            modifier = Modifier.fillMaxWidth().testTag("detail_reminder_value_$index"))
                        Row {
                            TextButton(onClick = {
                                val text = reminder.value
                                val initial = runCatching { LocalDate.parse(text.take(10)) }
                                    .getOrElse { state.now.atZone(vm.zoneId).toLocalDate() }
                                DatePickerDialog(context, { _, year, month, day ->
                                    val date = LocalDate.of(year, month + 1, day)
                                    val time = runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toLocalTime() }
                                        .getOrElse { LocalTime.of(9, 0) }
                                    TimePickerDialog(context, { _, hour, minute ->
                                        vm.onReminderValueChange(index, LocalDateTime.of(date,
                                            LocalTime.of(hour, minute)).format(timeFmt))
                                    }, time.hour, time.minute, true).show()
                                }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                            }, enabled = editable, modifier = Modifier.testTag("pick_reminder_$index")) {
                                Text("选择提醒时间")
                            }
                            TextButton(onClick = { vm.onReminderValueChange(index, "") }, enabled = editable,
                                modifier = Modifier.testTag(if (index == 0) "clear_remindAt" else "clear_reminder_$index")) {
                                Text("清除提醒时间")
                            }
                        }
                    } else {
                        OutlinedTextField(reminder.value, { vm.onReminderValueChange(index, it.filter(Char::isDigit)) },
                            enabled = editable, singleLine = true, label = { Text("截止前多少分钟") },
                            modifier = Modifier.fillMaxWidth().testTag("detail_reminder_value_$index"))
                    }
                    TextButton(onClick = { vm.onRemoveReminder(index) }, enabled = editable,
                        modifier = Modifier.testTag("remove_reminder_$index")) { Text("删除此提醒") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.onAddReminder(ReminderDraftKind.Specified) }, enabled = editable,
                    modifier = Modifier.testTag("add_specified_reminder")) { Text("新增指定提醒") }
                OutlinedButton(onClick = { vm.onAddReminder(ReminderDraftKind.Relative) }, enabled = editable,
                    modifier = Modifier.testTag("add_relative_reminder")) { Text("新增相对提醒") }
            }
            Text("相对提醒按截止时间提前；计划日期不会自动安排提醒。时间按 ${vm.zoneId.id} 输入。",
                style = MaterialTheme.typography.bodySmall)
        }
        item { ItemStatus(detail.original, state); Message(state.message) }
        item {
            if (detail.original.completedAt == null) {
                Button(onClick = vm::onSaveDetail, enabled = editable,
                    modifier = Modifier.fillMaxWidth().testTag("detail_save_button")) { Text("保存修改") }
                OutlinedButton(onClick = { vm.onComplete(detail.original.id) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("detail_complete_button")) { Text("完成事项") }
            } else {
                Text("已完成事项请先恢复再编辑", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.onRestore(detail.original.id) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("detail_restore_button")) { Text("恢复事项") }
            }
            TextButton(onClick = vm::onCancelDetail, enabled = !state.busy,
                modifier = Modifier.testTag("detail_cancel_button")) { Text("返回 / 取消修改") }
        }
    }
}

@Composable
private fun DateField(label: String, format: String, value: String, tag: String, enabled: Boolean,
    onChange: (String) -> Unit, onPick: () -> Unit) {
    Column {
        OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            label = { Text(label) }, placeholder = { Text(format) },
            modifier = Modifier.fillMaxWidth().testTag("detail_${tag}_field"))
        Row {
            TextButton(onClick = onPick, enabled = enabled, modifier = Modifier.testTag("pick_$tag")) { Text("选择$label") }
            TextButton(onClick = { onChange("") }, enabled = enabled, modifier = Modifier.testTag("clear_$tag")) { Text("清除$label") }
        }
    }
}

@Composable
private fun ItemRow(item: TodoItem, state: UiState, zone: ZoneId, tag: String, enabled: Boolean,
    onOpen: () -> Unit, onAction: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(item.title, fontWeight = FontWeight.Medium)
            item.plannedDate?.let { Text("计划：$it", style = MaterialTheme.typography.bodySmall) }
            item.dueAt?.let { Text("截止：${it.atZone(zone).format(timeFmt)}", style = MaterialTheme.typography.bodySmall) }
            if (item.reminders.isEmpty()) {
                Text(item.remindAt?.let { "提醒：${it.atZone(zone).format(timeFmt)}" } ?: "无提醒",
                    style = MaterialTheme.typography.bodySmall)
            } else item.reminders.forEachIndexed { index, reminder ->
                val rule = reminder.beforeDue?.let { "截止前 ${it.toMinutes()} 分钟" } ?: "指定"
                val status = when {
                    reminder.deliveredAt != null -> "已提醒"
                    reminder.outcome == ReminderOutcome.Scheduled -> "已安排"
                    reminder.outcome == ReminderOutcome.NotReady -> "未就绪"
                    else -> "未安排"
                }
                Text("提醒 ${index + 1}：$rule · ${reminder.at.atZone(zone).format(timeFmt)} · $status",
                    style = MaterialTheme.typography.bodySmall)
            }
            ItemStatus(item, state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.testTag("open_${item.id}")) { Text("详情") }
                TextButton(onClick = onAction, enabled = enabled,
                    modifier = Modifier.testTag(if (item.completedAt == null) "complete_${item.id}" else "restore_${item.id}")) {
                    Text(if (item.completedAt == null) "完成" else "恢复")
                }
                TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
                if (item.completedAt == null && item.reminders.any { it.outcome == ReminderOutcome.NotReady }) {
                    TextButton(onClick = onRetry, enabled = enabled,
                        modifier = Modifier.testTag("retry_reminders_${item.id}")) { Text("重试提醒") }
                }
            }
        }
    }
}

@Composable
private fun ItemStatus(item: TodoItem, state: UiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.id in state.deliveredIds) Text("已提醒", modifier = Modifier.testTag("delivered_${item.id}"),
            style = MaterialTheme.typography.labelMedium)
        if (item.isOverdue(state.now)) Text("逾期", color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("overdue_${item.id}"), style = MaterialTheme.typography.labelMedium)
        if (item.completedAt != null) Text("已完成", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StorageStatus(state: UiState, onRetry: () -> Unit) {
    if (state.loading) Text("正在准备本地数据…")
    state.storageError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("storage_error"))
        OutlinedButton(onClick = onRetry, enabled = !state.busy,
            modifier = Modifier.testTag("retry_storage_button")) { Text("重试数据迁移") }
    }
}

@Composable
private fun Message(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("message")) }
}
