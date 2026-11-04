package com.voicetodo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.domain.FocusPhase
import com.voicetodo.domain.FocusSessionState
import com.voicetodo.domain.FocusSettings
import java.time.Duration

@Composable
fun PomodoroScreen(vm: PomodoroViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settingsDraft
    if (settings != null) {
        FocusSettingsEditor(settings, state.busy, vm::onSettingsValueChange, vm::onSaveSettings, vm::onCancelSettings)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("pomodoro_screen"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("番茄", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("今日完成：${state.snapshot?.todayCount ?: 0} 个", modifier = Modifier.testTag("today_count"))
                    Text("今日时长：${formatSeconds(state.snapshot?.todaySeconds ?: 0)}", modifier = Modifier.testTag("today_duration"))
                    Text("最近七天：${state.snapshot?.lastSevenDaysCounts?.joinToString { "${it.first}: ${it.second} 个" } ?: ""}",
                modifier = Modifier.testTag("last_seven_days"))
        }
        item {
            val session = state.snapshot?.session
            when {
                session == null -> Text("暂无活动会话", modifier = Modifier.testTag("focus_state"))
                session.state == FocusSessionState.EndedPendingConfirmation -> {
                    Text("结束待确认；不会自动开始下一阶段", modifier = Modifier.testTag("focus_state"))
                    Button(onClick = vm::onConfirmEnded, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("confirm_ended_button")) { Text("确认完整结束") }
                }
                else -> {
                    Text(
                        when (session.state) {
                            FocusSessionState.Paused -> "已暂停，剩余 ${formatSeconds(remainingSeconds(session.endsAt.toEpochMilli(), state.now.toEpochMilli()))}"
                            else -> "进行中：${phaseName(session.phase)}，剩余 ${formatSeconds(remainingSeconds(session.endsAt.toEpochMilli(), state.now.toEpochMilli()))}"
                        },
                        modifier = Modifier.testTag("focus_state"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (session.state == FocusSessionState.Paused) {
                            Button(onClick = { vm.onResume() }, enabled = !state.busy, modifier = Modifier.testTag("resume_button")) { Text("继续") }
                        } else {
                            Button(onClick = { vm.onPause() }, enabled = !state.busy, modifier = Modifier.testTag("pause_button")) { Text("暂停") }
                        }
                        OutlinedButton(onClick = vm::onEndEarly, enabled = !state.busy, modifier = Modifier.testTag("end_early_button")) { Text("提前结束") }
                    }
                }
            }
        }
        item {
            if (state.snapshot?.session == null) {
                Button(onClick = { vm.onStart(FocusPhase.Focus) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("start_focus_button")) { Text("开始专注") }
                OutlinedButton(onClick = { vm.onStart(FocusPhase.ShortBreak) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("start_short_break_button")) { Text("手动开始短休息") }
                OutlinedButton(onClick = { vm.onStart(FocusPhase.LongBreak) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("start_long_break_button")) { Text("手动开始长休息") }
            }
            if (state.snapshot?.session?.state == FocusSessionState.EndedPendingConfirmation) {
                OutlinedButton(onClick = { vm.onStart(FocusPhase.ShortBreak) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("start_short_break_after_end_button")) { Text("手动开始短休息") }
                OutlinedButton(onClick = { vm.onStart(FocusPhase.LongBreak) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("start_long_break_after_end_button")) { Text("手动开始长休息") }
            }
            Text("绑定事项（可留空）", style = MaterialTheme.typography.labelMedium)
            state.items.take(10).forEach { item ->
                FilterChip(
                    selected = state.selectedItemId == item.id,
                    onClick = { vm.onPickItem(item.id) },
                    enabled = !state.busy && state.snapshot?.session == null,
                    label = { Text(item.title) },
                    modifier = Modifier.testTag("focus_item_${item.id}"),
                )
            }
            if (state.selectedItemId != null) {
                TextButton(onClick = { vm.onPickItem(null) }, modifier = Modifier.testTag("clear_focus_item_button")) {
                    Text("改为独立计时")
                }
            }
            state.items.firstOrNull { it.id == state.selectedItemId }?.let {
                Text("已绑定：${it.title}", modifier = Modifier.testTag("bound_item"))
            }
            TextButton(onClick = vm::onOpenSettings, enabled = !state.busy,
                modifier = Modifier.testTag("open_settings_button")) { Text("调整时长") }
        }
        item { state.message?.let { Text(it, modifier = Modifier.testTag("message")) } }
    }
}

@Composable
private fun FocusSettingsEditor(
    settings: FocusSettings,
    busy: Boolean,
    onChange: (FocusSettings) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("focus_settings"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
        Text("番茄设置", style = MaterialTheme.typography.titleLarge)
        NumberField("专注分钟", settings.focusMinutes) { onChange(settings.copy(focusMinutes = it)) }
        NumberField("短休息分钟", settings.shortBreakMinutes) { onChange(settings.copy(shortBreakMinutes = it)) }
        NumberField("长休息分钟", settings.longBreakMinutes) { onChange(settings.copy(longBreakMinutes = it)) }
        NumberField("每几次专注后长休息", settings.longBreakEvery) { onChange(settings.copy(longBreakEvery = it)) }
            Button(onClick = onSave, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("save_settings_button")) { Text("保存设置") }
            TextButton(onClick = onCancel, enabled = !busy,
                modifier = Modifier.testTag("cancel_settings_button")) { Text("取消") }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("setting_${label}"),
    )
}

private fun phaseName(phase: FocusPhase) = when (phase) {
    FocusPhase.Focus -> "专注"
    FocusPhase.ShortBreak -> "短休息"
    FocusPhase.LongBreak -> "长休息"
}

private fun formatSeconds(seconds: Long) = "${seconds / 60} 分钟"

private fun remainingSeconds(endsAtMillis: Long, nowMillis: Long) =
    Duration.ofMillis((endsAtMillis - nowMillis).coerceAtLeast(0)).seconds
