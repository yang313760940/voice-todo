package com.voicetodo.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voicetodo.ai.AiDraft
import com.voicetodo.ai.AiReminderDraft
import com.voicetodo.ai.AiReminderKind
import com.voicetodo.domain.Quadrant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun AiSettingsScreen(vm: TodoViewModel, state: UiState, form: AiSettingsForm) {
    val enabled = !state.busy
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("ai_settings_screen"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("高级设置 · 在线 AI", style = MaterialTheme.typography.headlineSmall)
            Text(
                "语音识别始终在本机离线完成。只有你主动点“用在线 AI 整理”时，" +
                    "应用才会把当前文字、当前日期时间和时区发送到你填写的服务。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OpenAI 兼容服务", fontWeight = FontWeight.Medium)
                    Text(
                        "地址示例：https://api.openai.com/v1 或 https://你的服务/v1。" +
                            "为保护密钥，远程地址必须使用 https；本机调试可用 127.0.0.1。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "密钥使用 Android Keystore 加密保存，不写入日志、备份或源码。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { vm.onAiSettingsChange(AiSettingsField.BaseUrl, it) },
                label = { Text("服务地址") },
                placeholder = { Text("https://host/v1") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("ai_base_url_field"),
            )
        }
        item {
            OutlinedTextField(
                value = form.model,
                onValueChange = { vm.onAiSettingsChange(AiSettingsField.Model, it) },
                label = { Text("模型") },
                placeholder = { Text("模型名称") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("ai_model_field"),
            )
        }
        item {
            OutlinedTextField(
                value = form.apiKey,
                onValueChange = { vm.onAiSettingsChange(AiSettingsField.ApiKey, it) },
                label = { Text("API 密钥") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("ai_api_key_field"),
            )
        }
        item {
            form.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ai_settings_error"))
            }
            state.aiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ai_settings_error"))
            }
            Message(state.message)
        }
        item {
            Button(onClick = vm::onSaveAiSettings, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("ai_settings_save")) {
                Text("保存配置")
            }
            if (state.aiConfigured) {
                OutlinedButton(onClick = vm::onClearAiSettings, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("ai_settings_clear")) {
                    Text("清除 AI 配置")
                }
            }
            TextButton(onClick = vm::onCloseAiSettings, enabled = enabled,
                modifier = Modifier.testTag("ai_settings_cancel")) {
                Text("返回")
            }
        }
    }
}

@Composable
fun AiDraftScreen(vm: TodoViewModel, state: UiState, draft: AiDraft, enabled: Boolean) {
    val context = LocalContext.current

    fun pickDate(current: String, onPicked: (String) -> Unit) {
        val initial = runCatching { LocalDate.parse(current.take(10)) }
            .getOrElse { state.now.atZone(vm.zoneId).toLocalDate() }
        DatePickerDialog(context, { _, year, month, day ->
            onPicked(LocalDate.of(year, month + 1, day).toString())
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    fun pickDateTime(current: String, onPicked: (String) -> Unit) {
        val initial = runCatching { LocalDateTime.parse(current.replace(' ', 'T')) }
            .getOrElse { LocalDateTime.ofInstant(state.now, vm.zoneId) }
        DatePickerDialog(context, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            TimePickerDialog(context, { _, hour, minute ->
                onPicked(LocalDateTime.of(date, LocalTime.of(hour, minute)).format(timeFmt))
            }, initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("ai_draft_screen"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("AI 整理草稿", style = MaterialTheme.typography.headlineSmall)
            Text(
                "确认前不会创建事项，也不会安排闹钟。请核对原文、时间和提醒后再确认。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("原始识别文字（请核对）", fontWeight = FontWeight.Medium)
                    Text(draft.sourceText, modifier = Modifier.testTag("ai_source_text"))
                }
            }
        }
        draft.clarification?.let { clarification ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("需要你确认", fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error)
                        Text(clarification, modifier = Modifier.testTag("ai_draft_clarification"))
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = draft.title,
                onValueChange = vm::onAiDraftTitleChange,
                label = { Text("事项名称") },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("ai_draft_title_field"),
            )
        }
        item {
            DateField(
                label = "计划日期",
                format = "yyyy-MM-dd",
                value = draft.plannedDate,
                tag = "plannedDate",
                enabled = enabled,
                onChange = vm::onAiDraftPlannedDateChange,
                onPick = { pickDate(draft.plannedDate, vm::onAiDraftPlannedDateChange) },
                tagPrefix = "ai_draft",
            )
        }
        item {
            DateField(
                label = "截止时间",
                format = "yyyy-MM-dd HH:mm",
                value = draft.dueAt,
                tag = "dueAt",
                enabled = enabled,
                onChange = vm::onAiDraftDueChange,
                onPick = { pickDateTime(draft.dueAt, vm::onAiDraftDueChange) },
                tagPrefix = "ai_draft",
            )
        }
        item { Text("提醒安排", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(draft.reminders, key = { _, reminder -> reminder.id }) { index, reminder ->
            AiReminderCard(
                index = index,
                reminder = reminder,
                enabled = enabled,
                onValueChange = { vm.onAiReminderValueChange(index, it) },
                onPickTime = { pickDateTime(reminder.value) { vm.onAiReminderValueChange(index, it) } },
                onRemove = { vm.onRemoveAiReminder(index) },
            )
        }
        item {
            if (draft.reminders.isEmpty()) {
                Text("未设置提醒；计划日期本身不会响铃。", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.onAddAiReminder(AiReminderKind.Specified) },
                    enabled = enabled, modifier = Modifier.testTag("ai_add_specified_reminder")) {
                    Text("新增指定提醒")
                }
                OutlinedButton(onClick = { vm.onAddAiReminder(AiReminderKind.Relative) },
                    enabled = enabled, modifier = Modifier.testTag("ai_add_relative_reminder")) {
                    Text("新增相对提醒")
                }
            }
        }
        item {
            Text("建议象限（你可以改）", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuadrantChip(draft.quadrant, Quadrant.ImportantUrgent, "重要且紧急", enabled,
                    vm::onAiDraftQuadrantChange, Modifier.weight(1f))
                QuadrantChip(draft.quadrant, Quadrant.ImportantNotUrgent, "重要不紧急", enabled,
                    vm::onAiDraftQuadrantChange, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuadrantChip(draft.quadrant, Quadrant.NotImportantUrgent, "不重要但紧急", enabled,
                    vm::onAiDraftQuadrantChange, Modifier.weight(1f))
                QuadrantChip(draft.quadrant, Quadrant.NotImportantNotUrgent, "不重要不紧急", enabled,
                    vm::onAiDraftQuadrantChange, Modifier.weight(1f))
            }
        }
        if (draft.clarification != null && !draft.hasUsableReminder) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
                        vm.onAiDraftClarificationAck(!draft.noReminderAcknowledged)
                    },
                ) {
                    Checkbox(
                        checked = draft.noReminderAcknowledged,
                        onCheckedChange = vm::onAiDraftClarificationAck,
                        enabled = enabled,
                        modifier = Modifier.testTag("ai_no_reminder_ack"),
                    )
                    Text("时间还不明确：我确认暂不设提醒，先保存事项",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            state.aiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ai_draft_error"))
            }
            Message(state.message)
        }
        item {
            Button(
                onClick = vm::onConfirmAiDraft,
                enabled = enabled && draft.title.isNotBlank() &&
                    !draft.requiresNoReminderAcknowledgement,
                modifier = Modifier.fillMaxWidth().testTag("ai_draft_confirm_button"),
            ) {
                Text(if (state.busy) "正在创建…" else "确认并创建事项")
            }
            TextButton(onClick = vm::onCancelAiDraft, enabled = enabled,
                modifier = Modifier.testTag("ai_draft_cancel_button")) {
                Text("取消草稿（保留文字）")
            }
        }
    }
}

@Composable
private fun AiReminderCard(
    index: Int,
    reminder: AiReminderDraft,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onPickTime: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("ai_draft_reminder_$index")) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (reminder.kind == AiReminderKind.Specified) "指定提醒 ${index + 1}"
                else "相对提醒 ${index + 1}",
                fontWeight = FontWeight.Medium,
            )
            if (reminder.kind == AiReminderKind.Specified) {
                OutlinedTextField(
                    value = reminder.value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("提醒时间") },
                    placeholder = { Text("yyyy-MM-dd HH:mm") },
                    modifier = Modifier.fillMaxWidth().testTag("ai_draft_reminder_value_$index"),
                )
                Row {
                    TextButton(onClick = onPickTime, enabled = enabled,
                        modifier = Modifier.testTag("ai_draft_pick_reminder_$index")) {
                        Text("选择提醒时间")
                    }
                    TextButton(onClick = { onValueChange("") }, enabled = enabled,
                        modifier = Modifier.testTag("ai_draft_clear_reminder_$index")) {
                        Text("清除")
                    }
                }
            } else {
                OutlinedTextField(
                    value = reminder.value,
                    onValueChange = { onValueChange(it.filter(Char::isDigit)) },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("截止前多少分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("ai_draft_reminder_value_$index"),
                )
            }
            TextButton(onClick = onRemove, enabled = enabled,
                modifier = Modifier.testTag("ai_draft_remove_reminder_$index")) {
                Text("删除此提醒")
            }
        }
    }
}

@Composable
private fun QuadrantChip(
    selected: Quadrant,
    value: Quadrant,
    label: String,
    enabled: Boolean,
    onSelect: (Quadrant) -> Unit,
    modifier: Modifier,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        enabled = enabled,
        modifier = modifier.testTag("ai_quadrant_${value.name}"),
    )
}
