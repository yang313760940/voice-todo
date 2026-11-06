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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.speech.AliyunVoiceCredentials
import com.voicetodo.speech.DoubaoSpeechSettings
import com.voicetodo.speech.VoiceLanguages
import com.voicetodo.speech.VoiceProvider
import java.time.DayOfWeek
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
            Text("语音与 AI", style = MaterialTheme.typography.headlineSmall)
            Text(
                "选择豆包或阿里云作为唯一供应商，语音转写和事项整理都走同一家。" +
                    "只有你主动录音时，本次 16kHz 单声道音频分片才会实时上传；" +
                    "转写完成后只发送本次文字、当前日期时间和时区用于整理，" +
                    "不发送历史事项或其他待办。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Card(Modifier.fillMaxWidth().testTag("voice_provider_card")) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务供应商", fontWeight = FontWeight.Medium)
                    Text(
                        "缺省不绑定任何供应商；首次使用前会再确认一次上传告知。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = form.provider == VoiceProvider.Doubao,
                            onClick = { vm.onVoiceProviderChange(VoiceProvider.Doubao) },
                            label = { Text("豆包") },
                            enabled = enabled,
                            modifier = Modifier.testTag("voice_provider_Doubao"),
                        )
                        FilterChip(
                            selected = form.provider == VoiceProvider.Aliyun,
                            onClick = { vm.onVoiceProviderChange(VoiceProvider.Aliyun) },
                            label = { Text("阿里云") },
                            enabled = enabled,
                            modifier = Modifier.testTag("voice_provider_Aliyun"),
                        )
                    }
                    Text(
                        if (form.provider == null) {
                            "尚未选择供应商；选择后才会显示该服务需要的凭据和语言。"
                        } else {
                            "已选择：${form.provider.label}。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("voice_provider_status"),
                    )
                }
            }
        }
        if (form.provider != null) {
            item {
                Card(Modifier.fillMaxWidth().testTag("voice_language_card")) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("识别语言", fontWeight = FontWeight.Medium)
                        Text(
                            "默认普通话；只列出${form.provider.label}支持的语言。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VoiceLanguages.supported(form.provider).forEach { language ->
                                FilterChip(
                                    selected = form.language == language.code,
                                    onClick = { vm.onVoiceLanguageChange(language.code) },
                                    label = { Text(language.label) },
                                    enabled = enabled,
                                    modifier = Modifier.testTag(
                                        "voice_language_${language.code}",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            when (form.provider) {
                VoiceProvider.Doubao -> {
                    item {
                        Card(Modifier.fillMaxWidth().testTag("doubao_credentials_card")) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("豆包凭据", fontWeight = FontWeight.Medium)
                                Text(
                                    "语音识别与方舟文本整理是两套凭据，必须分别填写。" +
                                        "密钥使用 Android Keystore 加密保存，不写入日志、备份或源码。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    item {
                        SecretField(
                            value = form.doubaoSpeechApiKey,
                            label = "豆包语音 API Key",
                            tag = "doubao_speech_api_key_field",
                            enabled = enabled,
                            onChange = {
                                vm.onVoiceSettingsChange(
                                    VoiceSettingsField.DoubaoSpeechApiKey,
                                    it,
                                )
                            },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = form.doubaoSpeechResourceId,
                            onValueChange = {
                                vm.onVoiceSettingsChange(
                                    VoiceSettingsField.DoubaoResourceId,
                                    it,
                                )
                            },
                            label = { Text("豆包语音资源 ID") },
                            placeholder = { Text(DoubaoSpeechSettings.DEFAULT_RESOURCE_ID) },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().testTag("doubao_resource_id_field"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = form.doubaoSpeechResourceId ==
                                    DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
                                onClick = {
                                    vm.onVoiceSettingsChange(
                                        VoiceSettingsField.DoubaoResourceId,
                                        DoubaoSpeechSettings.DEFAULT_RESOURCE_ID,
                                    )
                                },
                                label = { Text("2.0 小时版") },
                                enabled = enabled,
                                modifier = Modifier.testTag("speech_resource_default"),
                            )
                            FilterChip(
                                selected = form.doubaoSpeechResourceId ==
                                    DoubaoSpeechSettings.LEGACY_RESOURCE_ID,
                                onClick = {
                                    vm.onVoiceSettingsChange(
                                        VoiceSettingsField.DoubaoResourceId,
                                        DoubaoSpeechSettings.LEGACY_RESOURCE_ID,
                                    )
                                },
                                label = { Text("1.0 小时版") },
                                enabled = enabled,
                                modifier = Modifier.testTag("speech_resource_legacy"),
                            )
                        }
                        Text(
                            "若 2.0 小时版返回 403 未开通，可切换到 1.0 小时版后重试；" +
                                "应用不会把失败当成功。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item {
                        SecretField(
                            value = form.doubaoTextApiKey,
                            label = "豆包方舟 API Key",
                            tag = "doubao_text_api_key_field",
                            enabled = enabled,
                            onChange = {
                                vm.onVoiceSettingsChange(
                                    VoiceSettingsField.DoubaoTextApiKey,
                                    it,
                                )
                            },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = form.doubaoTextModel,
                            onValueChange = {
                                vm.onVoiceSettingsChange(
                                    VoiceSettingsField.DoubaoTextModel,
                                    it,
                                )
                            },
                            label = { Text("方舟模型或接入点 ID") },
                            placeholder = { Text("例如 ep-xxxxxxxx") },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().testTag("doubao_text_model_field"),
                        )
                    }
                }
                VoiceProvider.Aliyun -> {
                    item {
                        Card(Modifier.fillMaxWidth().testTag("aliyun_credentials_card")) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("阿里云百炼凭据", fontWeight = FontWeight.Medium)
                                Text(
                                    "一把百炼 API Key 同时用于实时识别和文本整理。" +
                                        "密钥使用 Android Keystore 加密保存，不写入日志、备份或源码。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    item {
                        SecretField(
                            value = form.aliyunApiKey,
                            label = "百炼 API Key",
                            tag = "aliyun_api_key_field",
                            enabled = enabled,
                            onChange = {
                                vm.onVoiceSettingsChange(VoiceSettingsField.AliyunApiKey, it)
                            },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = form.aliyunTextModel,
                            onValueChange = {
                                vm.onVoiceSettingsChange(VoiceSettingsField.AliyunTextModel, it)
                            },
                            label = { Text("百炼文本模型") },
                            placeholder = { Text(AliyunVoiceCredentials.DEFAULT_TEXT_MODEL) },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().testTag("aliyun_text_model_field"),
                        )
                    }
                }
            }
        }
        item {
            form.speechError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("voice_settings_error"))
            }
            state.aiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ai_settings_error"))
            }
            Text(
                if (state.speechConfigured) "语音识别：已配置" else "语音识别：未配置",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("speech_settings_status"),
            )
            Text(
                if (state.aiConfigured) "事项整理：已配置" else "事项整理：未配置",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ai_settings_status"),
            )
            Message(state.message)
        }
        item {
            Button(onClick = vm::onSaveVoiceSettings, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("voice_settings_save")) {
                Text("保存语音与 AI 配置")
            }
            if (state.voiceProvider != null) {
                OutlinedButton(onClick = vm::onClearVoiceSettings, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("voice_settings_clear")) {
                    Text("清除语音与 AI 配置")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().testTag("legacy_ai_settings_card")) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("旧配置（仅查看，不参与语音添加）", fontWeight = FontWeight.Medium)
                    if (form.legacyConfigured) {
                        Text(
                            "地址：${form.legacyBaseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "模型：${form.legacyModel} · 密钥已隐藏",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "这是旧版 OpenAI 兼容配置，保留查看，但不再参与转写或事项整理。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "未保留旧版 OpenAI 兼容配置。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            TextButton(onClick = vm::onCloseAiSettings, enabled = enabled,
                modifier = Modifier.testTag("ai_settings_cancel")) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    label: String,
    tag: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("留空保持不变") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
fun AiDraftScreen(vm: TodoViewModel, state: UiState, draft: AiDraft, enabled: Boolean) {
    val context = LocalContext.current
    var listMenuOpen by remember { mutableStateOf(false) }

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
                "整理完成后会显示 2 秒倒计时；修改、关闭或离开都会取消自动保存，改为手动确认。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.autoSaveDeadline != null) {
            item {
                val seconds = ((state.autoSaveRemainingMs + 999) / 1_000).coerceAtLeast(1)
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { vm.onCancelAutoSave() }
                        .testTag("ai_autosave_countdown"),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("$seconds 秒后自动保存", fontWeight = FontWeight.Medium)
                        Text(
                            "点此倒计时或修改任一草稿都会取消自动保存。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("原始识别文字（请核对）", fontWeight = FontWeight.Medium)
                    Text(draft.sourceText, modifier = Modifier.testTag("ai_source_text"))
                }
            }
        }
        itemsIndexed(state.aiDrafts, key = { _, candidate -> candidate.itemId }) { index, candidate ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { vm.onSelectAiDraft(candidate.itemId) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("ai_select_draft_$index"),
                ) {
                    Text("${if (candidate.itemId == draft.itemId) "正在核对 · " else ""}${index + 1}. ${candidate.title}")
                }
                TextButton(
                    onClick = { vm.onDeleteAiDraft(candidate.itemId) },
                    enabled = enabled,
                    modifier = Modifier.testTag("ai_delete_draft_$index"),
                ) {
                    Text("删除")
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
            OutlinedTextField(
                value = draft.note,
                onValueChange = vm::onAiDraftNoteChange,
                label = { Text("备注（不进入提醒正文）") },
                enabled = enabled,
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("ai_draft_note_field"),
            )
        }
        item {
            val listName = draft.listId?.let { id ->
                state.todoLists.firstOrNull { it.id == id }?.name
            } ?: "收集箱"
            OutlinedButton(
                onClick = { listMenuOpen = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("ai_draft_list_selector"),
            ) {
                Text("清单：$listName")
            }
            DropdownMenu(
                expanded = listMenuOpen,
                onDismissRequest = { listMenuOpen = false },
                modifier = Modifier.testTag("ai_draft_list_menu"),
            ) {
                DropdownMenuItem(
                    text = { Text("收集箱") },
                    onClick = { vm.onAiDraftListChange(null); listMenuOpen = false },
                    modifier = Modifier.testTag("ai_draft_list_inbox"),
                )
                state.todoLists.forEach { list ->
                    DropdownMenuItem(
                        text = { Text(list.name) },
                        onClick = { vm.onAiDraftListChange(list.id); listMenuOpen = false },
                        modifier = Modifier.testTag("ai_draft_list_${list.id}"),
                    )
                }
            }
            draft.listNotice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "没有具体提醒时间的事项会自动放入收集箱。",
                style = MaterialTheme.typography.bodySmall,
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
        item {
            Text("重复建议（你可以改）", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val choices = listOf(
                    null to "不重复",
                    RecurrenceFrequency.Daily to "每天",
                    RecurrenceFrequency.Weekly to "每周",
                    RecurrenceFrequency.Monthly to "每月",
                )
                choices.forEach { (frequency, label) ->
                    FilterChip(
                        selected = draft.recurrence?.frequency == frequency,
                        onClick = { vm.onAiRecurrenceChange(frequency) },
                        enabled = enabled,
                        label = { Text(label) },
                        modifier = Modifier.testTag("ai_recurrence_${frequency?.name ?: "None"}"),
                    )
                }
            }
            if (draft.recurrence?.frequency == RecurrenceFrequency.Weekly) {
                listOf((1..4).toList(), (5..7).toList()).forEach { days ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        days.forEach { day ->
                            val weekday = DayOfWeek.of(day)
                            FilterChip(
                                selected = weekday in draft.recurrence.weekdays,
                                onClick = { vm.onAiRecurrenceWeekdayToggle(weekday) },
                                enabled = enabled,
                                label = {
                                    Text(listOf(
                                        "周一", "周二", "周三", "周四", "周五", "周六", "周日",
                                    )[day - 1])
                                },
                                modifier = Modifier.testTag("ai_recurrence_day_$day"),
                            )
                        }
                    }
                }
            }
            if (draft.recurrence?.frequency == RecurrenceFrequency.Monthly) {
                OutlinedTextField(
                    value = draft.recurrence.monthDay,
                    onValueChange = vm::onAiRecurrenceMonthDayChange,
                    enabled = enabled,
                    label = { Text("每月几日（1—31，短月取末日）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("ai_recurrence_month_day"),
                )
            }
            draft.recurrence?.let {
                OutlinedTextField(
                    value = it.time,
                    onValueChange = vm::onAiRecurrenceTimeChange,
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("重复提醒时间（HH:mm）") },
                    placeholder = { Text("例如 18:30") },
                    modifier = Modifier.fillMaxWidth().testTag("ai_recurrence_time"),
                )
                Text(
                    "保存后会按此规则生成后续实例并安排提醒。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (draft.clarification != null) {
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
            OutlinedButton(
                onClick = vm::onConfirmAiDraftAsInbox,
                enabled = enabled && state.aiDrafts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("ai_inbox_all_button"),
            ) {
                Text("全部存入收集箱、不设提醒")
            }
            Button(
                onClick = vm::onConfirmAiDraft,
                enabled = enabled && state.aiDrafts.isNotEmpty() && state.aiDrafts.all {
                    it.title.isNotBlank() && !it.requiresNoReminderAcknowledgement &&
                        !it.recurrenceNeedsTime
                },
                modifier = Modifier.fillMaxWidth().testTag("ai_draft_confirm_button"),
            ) {
                Text(if (state.busy) "正在创建…" else "确认并创建全部 ${state.aiDrafts.size} 条事项")
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
