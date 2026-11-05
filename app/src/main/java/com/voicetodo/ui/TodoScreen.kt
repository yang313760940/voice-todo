package com.voicetodo.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Mic
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
import com.voicetodo.domain.TodoList
import com.voicetodo.domain.TodoView
import com.voicetodo.domain.ReminderOutcome
import com.voicetodo.domain.MissedReminder
import com.voicetodo.domain.RecurrenceFrequency
import com.voicetodo.domain.RecurrenceRule
import com.voicetodo.domain.RecurringDeleteScope
import com.voicetodo.domain.isOverdue
import com.voicetodo.domain.buildDisplayGroups
import com.voicetodo.domain.itemsForView
import com.voicetodo.domain.supportsUnscheduledFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun TodoScreen(vm: TodoViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onRefresh()
        onPauseOrDispose { }
    }
    val modalOpen = state.aiSettingsForm != null || state.aiDraft != null ||
        state.detail != null || state.addOpen || state.pendingRecurringDeleteId != null
    BackHandler(enabled = modalOpen) {
        when {
            state.aiSettingsForm != null -> vm.onCloseAiSettings()
            state.aiDraft != null -> vm.onCancelAiDraft()
            state.detail != null -> vm.onCancelDetail()
            state.addOpen -> vm.onCloseAdd()
            state.pendingRecurringDeleteId != null -> vm.onCancelRecurringDelete()
        }
    }
    val baseEnabled = !state.loading && !state.busy && state.storageError == null
    val enabled = baseEnabled && !state.aiBusy
    state.aiSettingsForm?.let {
        AiSettingsScreen(vm, state, it)
        return
    }
    state.aiDraft?.let {
        AiDraftScreen(vm, state, it, enabled)
        return
    }
    val detail = state.detail
    if (detail != null) {
        DetailScreen(vm, state, detail, enabled)
        return
    }
    if (state.addOpen) {
        AddTodoScreen(vm, state, enabled)
        return
    }
    TodoHomeScreen(vm, state, enabled)
    if (state.pendingRecurringDeleteId != null) {
        AlertDialog(
            onDismissRequest = vm::onCancelRecurringDelete,
            title = { Text("删除重复事项") },
            text = { Text("请选择只删除这一次，还是删除本次及以后的未完成实例。已完成历史会保留。") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { vm.onConfirmRecurringDelete(RecurringDeleteScope.ThisOnly) },
                        modifier = Modifier.testTag("delete_scope_this"),
                    ) { Text("仅删除本次") }
                    TextButton(
                        onClick = { vm.onConfirmRecurringDelete(RecurringDeleteScope.ThisAndFuture) },
                        modifier = Modifier.testTag("delete_scope_future"),
                    ) { Text("本次及以后") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = vm::onCancelRecurringDelete,
                    modifier = Modifier.testTag("delete_scope_cancel"),
                ) { Text("取消") }
            },
            modifier = Modifier.testTag("delete_scope_dialog"),
        )
    }
}

@Composable
private fun TodoHomeScreen(vm: TodoViewModel, state: UiState, enabled: Boolean) {
    val visible = itemsForView(
        state.items + state.completedItems,
        state.selectedView,
        state.now,
        vm.zoneId,
        state.unscheduledOnly,
    )
    var viewMenuOpen by remember { mutableStateOf(false) }
    var showCreateList by remember { mutableStateOf(false) }
    var showManageLists by remember { mutableStateOf(false) }
    val today = state.now.atZone(vm.zoneId).toLocalDate()
    val title = viewTitle(state.selectedView, state.todoLists)
    val emptyText = emptyViewText(state.selectedView)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("todo_list"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("view_title"))
                    Text(
                        if (state.selectedView == TodoView.Today) {
                            today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
                        } else "${visible.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box {
                    TextButton(onClick = { viewMenuOpen = true },
                        modifier = Modifier.testTag("view_selector")) {
                        Text("切换视图")
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = viewMenuOpen,
                        onDismissRequest = { viewMenuOpen = false },
                        modifier = Modifier.testTag("view_menu"),
                    ) {
                        ViewMenuItem("今天", "view_today", state.selectedView == TodoView.Today) {
                            vm.onSelectView(TodoView.Today); viewMenuOpen = false
                        }
                        ViewMenuItem("收集箱", "view_inbox", state.selectedView == TodoView.Inbox) {
                            vm.onSelectView(TodoView.Inbox); viewMenuOpen = false
                        }
                        ViewMenuItem("最近七天", "view_recent7", state.selectedView == TodoView.RecentSevenDays) {
                            vm.onSelectView(TodoView.RecentSevenDays); viewMenuOpen = false
                        }
                        ViewMenuItem("全部", "view_all", state.selectedView == TodoView.All) {
                            vm.onSelectView(TodoView.All); viewMenuOpen = false
                        }
                        ViewMenuItem("已完成", "view_completed", state.selectedView == TodoView.Completed) {
                            vm.onSelectView(TodoView.Completed); viewMenuOpen = false
                        }
                        if (state.todoLists.isNotEmpty()) {
                            HorizontalDivider()
                            state.todoLists.forEach { list ->
                                ViewMenuItem(
                                    list.name,
                                    "view_list_${list.id}",
                                    state.selectedView == TodoView.CustomList(list.id),
                                ) {
                                    vm.onSelectView(TodoView.CustomList(list.id)); viewMenuOpen = false
                                }
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("新建清单") },
                            onClick = { showCreateList = true; viewMenuOpen = false },
                            modifier = Modifier.testTag("view_create_list"),
                        )
                        DropdownMenuItem(
                            text = { Text("管理清单") },
                            onClick = { showManageLists = true; viewMenuOpen = false },
                            enabled = state.todoLists.isNotEmpty(),
                            modifier = Modifier.testTag("view_manage_lists"),
                        )
                    }
                }
            }
        }
        item { StorageStatus(state, vm::onRetryStorage) }
        if (state.missedReminders.isNotEmpty()) {
            item {
                MissedRemindersCard(
                    reminders = state.missedReminders,
                    zone = vm.zoneId,
                    enabled = enabled,
                    onComplete = vm::onComplete,
                    onReschedule = vm::onOpenDetail,
                )
            }
        }
        if (showCreateList) {
            item {
                CreateListPanel(
                    onCreate = { vm.onCreateList(it); showCreateList = false },
                    onDismiss = { showCreateList = false },
                )
            }
        }
        if (showManageLists) {
            item {
                ManageListsPanel(
                    lists = state.todoLists,
                    onRename = { id, name -> vm.onRenameList(id, name) },
                    onDelete = { vm.onDeleteList(it) },
                    onDismiss = { showManageLists = false },
                )
            }
        }
        item {
            Button(
                onClick = { vm.onOpenAdd(startVoice = true) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("voice_add_button"),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("语音添加事项")
            }
            OutlinedButton(
                onClick = { vm.onOpenAdd(startVoice = false) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("text_add_button"),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("文字添加")
            }
        }
        if (state.selectedView.supportsUnscheduledFilter) {
            item {
                FilterChip(
                    selected = state.unscheduledOnly,
                    onClick = vm::onToggleUnscheduledFilter,
                    label = { Text("仅未安排时间") },
                    enabled = enabled,
                    modifier = Modifier.testTag("unscheduled_filter"),
                )
            }
        }
        item {
            InputError(state.inputError)
            Message(state.message)
            HorizontalDivider()
            Text("${visible.size} 项", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("view_count"))
        }
        if (visible.isEmpty()) {
            item { Text(emptyText, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("empty_view")) }
        }
        val completedView = state.selectedView == TodoView.Completed
        val displayGroups = buildDisplayGroups(visible, today)
        var rowIndex = 0
        displayGroups.forEach { group ->
            val backlogSeriesId = group.seriesId
            if (group.backlog && backlogSeriesId != null) {
                val expanded = backlogSeriesId in state.expandedBacklogSeries
                item(key = "backlog_$backlogSeriesId") {
                    BacklogHeader(
                        seriesId = backlogSeriesId,
                        count = group.items.size,
                        label = state.seriesLabels[backlogSeriesId],
                        expanded = expanded,
                        enabled = enabled,
                        onToggle = { vm.onToggleBacklog(backlogSeriesId) },
                    )
                }
            }
            val rows = if (group.backlog) {
                if (backlogSeriesId != null && backlogSeriesId in state.expandedBacklogSeries) group.items
                else emptyList()
            } else {
                group.items
            }
            rows.forEach { child ->
                val index = rowIndex++
                item(key = child.id) {
                    ItemRow(
                        item = child, state = state, zone = vm.zoneId,
                        tag = if (completedView) "completed_row_$index" else "item_row_$index",
                        enabled = enabled,
                        onOpen = { vm.onOpenDetail(child.id) },
                        onAction = { if (completedView) vm.onRestore(child.id) else vm.onComplete(child.id) },
                        onDelete = { vm.onDelete(child.id) },
                        onRetry = { vm.onRetryReminders(child.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MissedRemindersCard(
    reminders: List<MissedReminder>,
    zone: ZoneId,
    enabled: Boolean,
    onComplete: (String) -> Unit,
    onReschedule: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("missed_reminders")) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("错过的提醒（${reminders.size}）", style = MaterialTheme.typography.titleMedium)
            Text("不会集中补响。请完成事项或重新安排未来时间。",
                style = MaterialTheme.typography.bodySmall)
            reminders.forEach { missed ->
                Column(Modifier.fillMaxWidth().testTag("missed_${missed.reminderId}")) {
                    Text(missed.item.title, fontWeight = FontWeight.Medium)
                    Text(
                        "原提醒时间：${missed.originalAt.atZone(zone).format(timeFmt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onComplete(missed.item.id) },
                            enabled = enabled,
                            modifier = Modifier.testTag("complete_missed_${missed.reminderId}"),
                        ) { Text("完成") }
                        TextButton(
                            onClick = { onReschedule(missed.item.id) },
                            enabled = enabled,
                            modifier = Modifier.testTag("reschedule_missed_${missed.reminderId}"),
                        ) { Text("重新安排") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewMenuItem(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (selected) Text("当前", style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun BacklogHeader(
    seriesId: String,
    count: Int,
    label: String?,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("backlog_$seriesId")
            .clickable(enabled = enabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "积压 $count 项 · ${label ?: "重复安排"}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).testTag("backlog_label_$seriesId"),
        )
        Text(if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("backlog_toggle_$seriesId"))
    }
}

private fun viewTitle(view: TodoView, lists: List<TodoList>): String = when (view) {
    TodoView.Today -> "今天"
    TodoView.Inbox -> "收集箱"
    TodoView.RecentSevenDays -> "最近七天"
    TodoView.All -> "全部"
    TodoView.Completed -> "已完成"
    is TodoView.CustomList -> lists.firstOrNull { it.id == view.listId }?.name ?: "清单"
}

private fun emptyViewText(view: TodoView): String = when (view) {
    TodoView.Today -> "今天没有要处理的事项"
    TodoView.Inbox -> "收集箱是空的"
    TodoView.RecentSevenDays -> "最近七天没有安排"
    TodoView.All -> "还没有未完成事项"
    TodoView.Completed -> "还没有完成的事项"
    is TodoView.CustomList -> "这个清单还没有事项"
}

@Composable
private fun CreateListPanel(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().testTag("create_list_panel")) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("新建清单", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("清单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("new_list_name"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onCreate(name.trim()) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.testTag("create_list_confirm"),
                ) { Text("创建") }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("create_list_cancel")) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun ManageListsPanel(
    lists: List<TodoList>,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<TodoList?>(null) }
    var editName by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().testTag("manage_lists_panel")) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (editing == null) "管理清单" else "重命名清单",
                style = MaterialTheme.typography.titleMedium)
            if (editing == null) {
                lists.forEach { list ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(list.name, Modifier.weight(1f))
                        TextButton(
                            onClick = { editing = list; editName = list.name },
                            modifier = Modifier.testTag("rename_list_${list.id}"),
                        ) { Text("重命名") }
                        TextButton(
                            onClick = { onDelete(list.id) },
                            modifier = Modifier.testTag("delete_list_${list.id}"),
                        ) { Text("删除") }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("manage_lists_close")) {
                    Text("关闭")
                }
            } else {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("清单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_list_name"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onRename(editing!!.id, editName.trim()); editing = null },
                        enabled = editName.isNotBlank(),
                        modifier = Modifier.testTag("rename_list_confirm"),
                    ) { Text("保存") }
                    TextButton(
                        onClick = { editing = null },
                        modifier = Modifier.testTag("manage_lists_close"),
                    ) { Text("返回") }
                }
            }
        }
    }
}

@Composable
private fun AddTodoScreen(vm: TodoViewModel, state: UiState, enabled: Boolean) {
    val baseEnabled = !state.loading && !state.busy && state.storageError == null
    val targetName = state.addTargetListId?.let { id ->
        state.todoLists.firstOrNull { it.id == id }?.name
    }
    var hourText by remember { mutableStateOf("") }
    var minuteText by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("todo_list"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("添加事项", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::onCloseAdd, enabled = !state.busy,
                    modifier = Modifier.testTag("add_cancel_button")) { Text("取消") }
            }
            Text(
                if (targetName != null) "将加入清单「$targetName」" else "将加入收集箱",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("add_target"),
            )
        }
        item {
            state.capabilities?.let { cap ->
                val parts = listOf(
                    if (cap.canScheduleExactAlarm) "精确闹钟 ✓" else "精确闹钟 ✗",
                    if (cap.canPostNotifications) "通知 ✓" else "通知 ✗",
                    if (cap.canRecordAudio) "麦克风 ✓" else "麦克风 ✗",
                )
                Text("权限：" + parts.joinToString("  "), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("record_permission_status"))
            }
        }
        item { StorageStatus(state, vm::onRetryStorage) }
        item {
            Button(onClick = vm::onToggleRecord, modifier = Modifier.fillMaxWidth().testTag("voice_button"),
                enabled = enabled && state.recordState != RecordState.Transcribing &&
                    (state.recordState == RecordState.Recording ||
                        (state.speechReady && state.capabilities?.canRecordAudio == true))) {
                Text(when (state.recordState) {
                    RecordState.Idle -> if (state.draftText.isBlank()) "开始录音" else "重新录音"
                    RecordState.Recording -> "结束录音"
                    RecordState.Transcribing -> "识别中…"
                })
            }
            if (state.recordState != RecordState.Idle) {
                TextButton(onClick = vm::onCancelRecording, enabled = enabled,
                    modifier = Modifier.testTag("cancel_record_button")) {
                    Text(if (state.recordState == RecordState.Recording) "取消录音" else "取消识别")
                }
            }
        }
        item {
            OutlinedTextField(value = state.draftText, onValueChange = vm::onDraftTextChange,
                label = { Text("识别文字 / 标题（可编辑）") }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("title_field"))
        }
        item {
            Card(Modifier.fillMaxWidth().testTag("ai_card")) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("在线 AI 整理（可选）", fontWeight = FontWeight.Medium)
                    Text(
                        "语音识别始终在本机离线完成。在线 AI 只接收你这次提交的文字、当前日期时间和时区，" +
                            "不发送录音、历史事项或其他待办。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (state.aiConfigured) "AI：已配置（密钥加密保存）" else "AI：未配置",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("ai_config_status"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = vm::onOrganizeWithAi,
                            enabled = enabled && (state.draftText.isNotBlank() || !state.aiConfigured),
                            modifier = Modifier.testTag("ai_organize_button"),
                        ) {
                            Text(if (state.aiConfigured) "用在线 AI 整理" else "配置 AI 服务")
                        }
                        TextButton(onClick = vm::onOpenAiSettings, enabled = baseEnabled,
                            modifier = Modifier.testTag("ai_settings_button")) {
                            Text("高级设置")
                        }
                    }
                    if (state.aiBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("AI 整理中…", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::onCancelAiRequest, enabled = baseEnabled,
                            modifier = Modifier.testTag("cancel_ai_request")) {
                            Text("取消 AI 请求（保留文字）")
                        }
                    }
                    state.aiError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("ai_error"))
                    }
                }
            }
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
            Button(onClick = vm::onOpenManualCreate,
                enabled = enabled && state.recordState == RecordState.Idle,
                modifier = Modifier.fillMaxWidth().testTag("manual_create_button")) {
                Text("填写完整详情并创建")
            }
            Button(onClick = vm::onSave, enabled = enabled && state.recordState == RecordState.Idle,
                modifier = Modifier.fillMaxWidth().testTag("save_button")) { Text("保存并安排提醒") }
            OutlinedButton(onClick = vm::onSaveWithoutReminder,
                enabled = enabled && state.recordState == RecordState.Idle,
                modifier = Modifier.fillMaxWidth().testTag("save_without_reminder_button")) { Text("不设提醒直接保存") }
            TextButton(onClick = vm::onCancel, enabled = enabled,
                modifier = Modifier.testTag("cancel_button")) { Text("取消草稿") }
        }
        item {
            InputError(state.inputError)
            Message(state.message)
        }
    }
}

@Composable
private fun DetailScreen(vm: TodoViewModel, state: UiState, detail: DetailDraft, enabled: Boolean) {
    val context = LocalContext.current
    val original = detail.original
    val editable = enabled && original?.completedAt == null
    var listMenuOpen by remember { mutableStateOf(false) }
    val listName = detail.listId?.let { id ->
        state.todoLists.firstOrNull { it.id == id }?.name
    } ?: "收集箱"
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
        item {
            Text(if (original == null) "创建事项" else "事项详情",
                style = MaterialTheme.typography.headlineSmall)
            StorageStatus(state, vm::onRetryStorage)
        }
        item {
            OutlinedTextField(value = detail.title, onValueChange = vm::onDetailTitleChange,
                label = { Text("标题") }, enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag("detail_title_field"))
        }
        if (original == null) {
            item { RecurrenceEditor(detail, vm) }
        } else {
            detail.seriesRule?.let { rule ->
                item {
                    Text("重复：${recurrenceLabel(rule)} · 本次 ${original.occurrenceDate ?: ""}",
                        modifier = Modifier.testTag("detail_recurrence_summary"))
                    TextButton(onClick = { vm.onStopRecurrence(original.id) }, enabled = enabled,
                        modifier = Modifier.testTag("stop_recurrence_button")) {
                        Text("结束整个重复安排")
                    }
                }
            }
        }
        if (original != null || detail.repeat == RepeatDraft.None) {
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
        }
        item {
            Box {
                OutlinedButton(
                    onClick = { listMenuOpen = true },
                    enabled = editable,
                    modifier = Modifier.testTag("detail_list_selector"),
                ) {
                    Text("清单：$listName")
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = listMenuOpen,
                    onDismissRequest = { listMenuOpen = false },
                    modifier = Modifier.testTag("detail_list_menu"),
                ) {
                    DropdownMenuItem(
                        text = { Text("收集箱") },
                        onClick = { vm.onDetailListChange(null); listMenuOpen = false },
                        modifier = Modifier.testTag("detail_list_inbox"),
                    )
                    state.todoLists.forEach { list ->
                        DropdownMenuItem(
                            text = { Text(list.name) },
                            onClick = { vm.onDetailListChange(list.id); listMenuOpen = false },
                            modifier = Modifier.testTag("detail_list_${list.id}"),
                        )
                    }
                }
            }
            Text("每个事项只属于一个清单；未选择时进入收集箱。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (original != null || detail.repeat == RepeatDraft.None) {
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
                    if (original != null) {
                        TextButton(onClick = { vm.onRemoveReminder(index) }, enabled = editable,
                            modifier = Modifier.testTag("remove_reminder_$index")) { Text("删除此提醒") }
                    }
                    }
                }
            }
            item {
                if (original != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.onAddReminder(ReminderDraftKind.Specified) }, enabled = editable,
                            modifier = Modifier.testTag("add_specified_reminder")) { Text("新增指定提醒") }
                        OutlinedButton(onClick = { vm.onAddReminder(ReminderDraftKind.Relative) }, enabled = editable,
                            modifier = Modifier.testTag("add_relative_reminder")) { Text("新增相对提醒") }
                    }
                }
                Text("相对提醒按截止时间提前；计划日期不会自动安排提醒。时间按 ${vm.zoneId.id} 输入。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (original != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().testTag("read_aloud_row")) {
                    Column(Modifier.weight(1f)) {
                        Text("朗读事项", fontWeight = FontWeight.Medium)
                        Text("到点用本机语音播报标题；缺少本地语音资源时显示原因并改用提示音，断网也可用。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = original.readAloud,
                        onCheckedChange = { vm.onSetReadAloud(original.id, it) },
                        enabled = enabled && original.completedAt == null,
                        modifier = Modifier.testTag("read_aloud_switch"),
                    )
                }
            }
            if (original.readAloud) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().testTag("read_aloud_when_locked_row")) {
                        Column(Modifier.weight(1f)) {
                            Text("锁屏朗读", fontWeight = FontWeight.Medium)
                            Text("默认关闭：锁屏时只响提示音，不播报标题；仅在你明确允许后读出正文。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = original.readAloudWhenLocked,
                            onCheckedChange = { vm.onSetReadAloudWhenLocked(original.id, it) },
                            enabled = enabled && original.completedAt == null,
                            modifier = Modifier.testTag("read_aloud_when_locked_switch"),
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().testTag("persistent_ring_row")) {
                    Column(Modifier.weight(1f)) {
                        Text("持续响铃", fontWeight = FontWeight.Medium)
                        Text("默认关闭：普通提示音与振动。开启后持续响铃 60 秒并保留通知，手动关闭立即停止。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = original.persistentRing,
                        onCheckedChange = { vm.onSetPersistentRing(original.id, it) },
                        enabled = enabled && original.completedAt == null,
                        modifier = Modifier.testTag("persistent_ring_switch"),
                    )
                }
            }
        }
        item {
            original?.let { ItemStatus(it, state) }
            original?.let { ReminderResolutionText(it, state.now, vm.zoneId) }
            Message(state.message)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (original == null) {
                    Button(onClick = vm::onSaveDetail, enabled = editable,
                        modifier = Modifier.fillMaxWidth().testTag("detail_save_button")) { Text("创建事项") }
                } else if (original.completedAt == null) {
                    if (original.seriesId != null) {
                        Text("保存范围", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = vm::onSaveDetailThisOnly, enabled = editable,
                            modifier = Modifier.fillMaxWidth().testTag("detail_save_this_only")) { Text("仅本次保存") }
                        OutlinedButton(onClick = vm::onSaveDetailThisAndFuture, enabled = editable,
                            modifier = Modifier.fillMaxWidth().testTag("detail_save_this_and_future")) { Text("本次及以后保存") }
                    } else {
                        Button(onClick = vm::onSaveDetail, enabled = editable,
                            modifier = Modifier.fillMaxWidth().testTag("detail_save_button")) { Text("保存修改") }
                    }
                    OutlinedButton(onClick = { vm.onComplete(original.id) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("detail_complete_button")) { Text("完成事项") }
                } else {
                    Text("已完成事项请先恢复再编辑", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.onRestore(original.id) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("detail_restore_button")) { Text("恢复事项") }
                }
                TextButton(onClick = vm::onCancelDetail, enabled = !state.busy,
                    modifier = Modifier.testTag("detail_cancel_button")) { Text("返回 / 取消修改") }
            }
        }
    }
}

@Composable
private fun RecurrenceEditor(detail: DetailDraft, vm: TodoViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("重复", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                RepeatDraft.None to "不重复",
                RepeatDraft.Daily to "每天",
                RepeatDraft.Weekly to "每周",
                RepeatDraft.Monthly to "每月",
            ).forEach { (kind, label) ->
                FilterChip(
                    selected = detail.repeat == kind,
                    onClick = { vm.onRepeatChange(kind) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("repeat_${kind.name.lowercase()}"),
                )
            }
        }
        when (detail.repeat) {
            RepeatDraft.Weekly -> {
                Text("选择星期", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.take(4).forEach { day -> WeekdayChip(day, detail, vm) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.drop(4).forEach { day -> WeekdayChip(day, detail, vm) }
                    TextButton(onClick = vm::onWorkdaysSelected,
                        modifier = Modifier.testTag("repeat_workdays")) { Text("工作日") }
                }
            }
            RepeatDraft.Monthly -> {
                OutlinedTextField(
                    value = detail.monthDay,
                    onValueChange = vm::onMonthDayChange,
                    label = { Text("每月日期（1 至 31）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("repeat_month_day"),
                )
            }
            else -> Unit
        }
        if (detail.repeat != RepeatDraft.None) {
            OutlinedTextField(
                value = detail.repeatTime,
                onValueChange = vm::onRepeatTimeChange,
                label = { Text("重复提醒时间（时:分）") },
                placeholder = { Text("09:00") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("repeat_time_field"),
            )
            Text("重复实例按 ${vm.zoneId.id} 的当地日历生成；每月 31 日在短月取月末；只预排接下来两次实例。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeekdayChip(day: DayOfWeek, detail: DetailDraft, vm: TodoViewModel) {
    FilterChip(
        selected = day in detail.weekdays,
        onClick = { vm.onWeekdayToggle(day) },
        label = { Text(weekdayLabel(day)) },
        modifier = Modifier.testTag("repeat_weekday_${day.name}"),
    )
}

private fun weekdayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

internal fun recurrenceLabel(rule: RecurrenceRule): String {
    val time = rule.reminderTimes.joinToString("、") { it.toString() }
    return when (rule.frequency) {
        RecurrenceFrequency.Daily -> "每天 $time"
        RecurrenceFrequency.Weekly ->
            "每周 ${rule.weekdays.sorted().joinToString("") { weekdayLabel(it) }} $time"
        RecurrenceFrequency.Monthly -> "每月 ${rule.dayOfMonth} 日 $time"
    }
}

@Composable
internal fun DateField(label: String, format: String, value: String, tag: String, enabled: Boolean,
    onChange: (String) -> Unit, onPick: () -> Unit, tagPrefix: String = "detail") {
    val fieldTag = if (tagPrefix == "detail") "detail_${tag}_field" else "${tagPrefix}_${tag}_field"
    val pickTag = if (tagPrefix == "detail") "pick_$tag" else "${tagPrefix}_pick_$tag"
    val clearTag = if (tagPrefix == "detail") "clear_$tag" else "${tagPrefix}_clear_$tag"
    Column {
        OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            label = { Text(label) }, placeholder = { Text(format) },
            modifier = Modifier.fillMaxWidth().testTag(fieldTag))
        Row {
            TextButton(onClick = onPick, enabled = enabled,
                modifier = Modifier.testTag(pickTag)) { Text("选择$label") }
            TextButton(onClick = { onChange("") }, enabled = enabled,
                modifier = Modifier.testTag(clearTag)) { Text("清除$label") }
        }
    }
}

@Composable
private fun ItemRow(item: TodoItem, state: UiState, zone: ZoneId, tag: String, enabled: Boolean,
    onOpen: () -> Unit, onAction: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(item.title, fontWeight = FontWeight.Medium)
            Text("象限：${item.quadrant.label}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("todo_quadrant_${item.id}"))
            item.listId?.let { listId ->
                state.todoLists.firstOrNull { it.id == listId }?.let {
                    Text("清单：${it.name}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("todo_list_name_${item.id}"))
                }
            }
            if (item.seriesId != null) {
                Text("重复实例${state.seriesLabels[item.seriesId]?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("recurrence_${item.id}"))
            }
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
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.testTag("delete_${item.id}"),
                ) { Text("删除") }
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

/**
 * 详情页显示上次通知处理的结果：完成、已关闭本次提醒、或已延后到新的时刻。
 * 结果来自持久化事项状态（dismissedAt / completedAt / reminders），重开应用仍可见。
 */
@Composable
private fun ReminderResolutionText(item: TodoItem, now: java.time.Instant, zone: ZoneId) {
    // 仅“已延后/已关闭”需要额外说明；完成状态由 ItemStatus 展示。
    val snoozedTo = item.reminders
        .filter { it.outcome == ReminderOutcome.Scheduled && it.deliveredAt == null && it.at.isAfter(now) }
        .map { it.at }.minOrNull()
    val text = when {
        item.completedAt != null -> null
        item.dismissedAt != null && snoozedTo != null ->
            "本次提醒已延后，已重新安排至 ${snoozedTo.atZone(zone).format(timeFmt)}"
        item.dismissedAt != null -> "本次提醒已关闭，事项未完成"
        else -> null
    }
    text?.let {
        Text(it, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("reminder_resolution_${item.id}"))
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
internal fun Message(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("message")) }
}

@Composable
internal fun InputError(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("input_error")) }
}
