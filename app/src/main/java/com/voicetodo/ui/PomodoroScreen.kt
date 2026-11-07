package com.voicetodo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.domain.FocusPhase
import com.voicetodo.domain.FocusSessionState
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FocusSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun PomodoroScreen(vm: PomodoroViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settingsDraft
    if (settings != null) {
        FocusSettingsEditor(settings, state.busy, vm::onSettingsValueChange, vm::onSaveSettings, vm::onCancelSettings)
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("pomodoro_screen"),
    ) {
        PomodoroTopBar(
            snapshot = state.snapshot,
            onOpenSettings = vm::onOpenSettings,
            onShowStats = vm::onToggleStats,
            onToggleItemPicker = vm::onToggleItemPicker,
        )
        Spacer(Modifier.height(16.dp))
        TimerModeSelector(state.mode, vm::onSelectMode)
        Spacer(Modifier.height(20.dp))

        val snapshot = state.snapshot
        val session = snapshot?.session
        val now = state.now
        when (state.mode) {
            TimerMode.Pomodoro -> {
                val focusSettings = snapshot?.settings ?: FocusSettings()
                val totalSeconds = session?.durationSeconds ?: (focusSettings.focusMinutes * 60L)
                val remaining = if (session != null) {
                    Duration.ofMillis((session.endsAt.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)).seconds
                } else {
                    totalSeconds
                }
                val progress = if (totalSeconds > 0) {
                    ((totalSeconds - remaining).toFloat() / totalSeconds).coerceIn(0f, 1f)
                } else 0f
                TimeRing(
                    progress = progress,
                    display = formatClock(remaining),
                    subtitle = when {
                        session == null -> "专注"
                        session.state == FocusSessionState.Paused -> "${phaseName(session.phase)} · 已暂停"
                        else -> phaseName(session.phase)
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    tag = "focus_ring",
                )
                PomodoroControls(session, state, vm)
            }
            TimerMode.Stopwatch -> {
                val elapsed = vm.stopwatchSeconds(now)
                val progress = ((elapsed % 3600).toFloat() / 3600f).coerceIn(0f, 1f)
                TimeRing(
                    progress = progress,
                    display = formatClock(elapsed),
                    subtitle = if (state.stopwatchRunning) "正计时 · 进行中" else "正计时",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    tag = "stopwatch_ring",
                )
                StopwatchControls(state, vm)
            }
        }

        Spacer(Modifier.height(12.dp))
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().testTag("message"))
        }
    }

    val statsSnapshot = state.snapshot
    if (state.showStats && statsSnapshot != null) {
        StatsDialog(statsSnapshot, vm::onToggleStats)
    }
}

@Composable
private fun PomodoroTopBar(
    snapshot: FocusSnapshot?,
    onOpenSettings: () -> Unit,
    onShowStats: () -> Unit,
    onToggleItemPicker: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("番茄统计", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "今日 ${snapshot?.todayCount ?: 0} 番茄",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("today_count"),
            )
            Text(
                formatDuration(snapshot?.todaySeconds ?: 0),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("today_duration"),
            )
            Text(
                "近 7 天 ${snapshot?.lastSevenDaysCounts?.sumOf { it.second } ?: 0} 番茄",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("last_seven_days"),
            )
        }
        SettingsMenu(onOpenSettings = onOpenSettings, onShowStats = onShowStats, onToggleItemPicker = onToggleItemPicker)
    }
}

@Composable
private fun SettingsMenu(
    onOpenSettings: () -> Unit,
    onShowStats: () -> Unit,
    onToggleItemPicker: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("settings_button")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "设置选项")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("专注设置") },
                onClick = { expanded = false; onOpenSettings() },
                modifier = Modifier.testTag("menu_focus_settings"),
            )
            DropdownMenuItem(
                text = { Text("绑定事项") },
                onClick = { expanded = false; onToggleItemPicker() },
                modifier = Modifier.testTag("menu_bind_item"),
            )
            DropdownMenuItem(
                text = { Text("统计详情") },
                onClick = { expanded = false; onShowStats() },
                modifier = Modifier.testTag("menu_stats"),
            )
        }
    }
}

@Composable
private fun TimerModeSelector(mode: TimerMode, onSelect: (TimerMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == TimerMode.Pomodoro,
            onClick = { onSelect(TimerMode.Pomodoro) },
            label = { Text("番茄计时") },
            modifier = Modifier.testTag("mode_pomodoro"),
        )
        FilterChip(
            selected = mode == TimerMode.Stopwatch,
            onClick = { onSelect(TimerMode.Stopwatch) },
            label = { Text("正计时") },
            modifier = Modifier.testTag("mode_stopwatch"),
        )
    }
}

@Composable
private fun TimeRing(
    progress: Float,
    display: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    tag: String,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.testTag(tag), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            if (progress > 0f) {
                drawArc(
                    color = active,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(display, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PomodoroControls(
    session: com.voicetodo.domain.FocusSession?,
    state: PomodoroUiState,
    vm: PomodoroViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            session == null -> {
                Button(
                    onClick = { vm.onStart(FocusPhase.Focus) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("start_focus_button"),
                ) { Text("开始专注", style = MaterialTheme.typography.titleMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.onStart(FocusPhase.ShortBreak) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).testTag("start_short_break_button"),
                    ) { Text("短休息") }
                    OutlinedButton(
                        onClick = { vm.onStart(FocusPhase.LongBreak) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).testTag("start_long_break_button"),
                    ) { Text("长休息") }
                }
            }
            session.state == FocusSessionState.EndedPendingConfirmation -> {
                Button(
                    onClick = vm::onConfirmEnded,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("confirm_ended_button"),
                ) { Text("确认完成") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.onStart(FocusPhase.ShortBreak) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).testTag("start_short_break_button"),
                    ) { Text("短休息") }
                    OutlinedButton(
                        onClick = { vm.onStart(FocusPhase.LongBreak) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).testTag("start_long_break_button"),
                    ) { Text("长休息") }
                }
            }
            session.state == FocusSessionState.Paused -> {
                Button(
                    onClick = { vm.onResume() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("resume_button"),
                ) { Text("继续") }
                TextButton(
                    onClick = vm::onEndEarly,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("end_early_button"),
                ) { Text("提前结束") }
            }
            else -> {
                Button(
                    onClick = { vm.onPause() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("pause_button"),
                ) { Text("暂停") }
                TextButton(
                    onClick = vm::onEndEarly,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("end_early_button"),
                ) { Text("提前结束") }
            }
        }

        if (session == null) {
            if (state.showItemPicker) {
                ItemPicker(state.items, state.selectedItemId, state.busy, vm::onPickItem)
            }
            state.items.firstOrNull { it.id == state.selectedItemId }?.let {
                Text("已绑定：${it.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bound_item"))
            }
        }
    }
}

@Composable
private fun ItemPicker(
    items: List<com.voicetodo.domain.TodoItem>,
    selectedItemId: String?,
    busy: Boolean,
    onPickItem: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("item_picker"),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items.take(10), key = { it.id }) { item ->
            FilterChip(
                selected = selectedItemId == item.id,
                onClick = { onPickItem(if (selectedItemId == item.id) null else item.id) },
                enabled = !busy,
                label = { Text(item.title) },
                modifier = Modifier.testTag("focus_item_${item.id}"),
            )
        }
    }
}

@Composable
private fun StopwatchControls(state: PomodoroUiState, vm: PomodoroViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            !state.stopwatchRunning && state.stopwatchResumedAt == null && state.stopwatchElapsedSeconds == 0L -> {
                Button(
                    onClick = vm::onStartStopwatch,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("start_stopwatch_button"),
                ) { Text("开始", style = MaterialTheme.typography.titleMedium) }
            }
            state.stopwatchRunning -> {
                Button(
                    onClick = vm::onPauseStopwatch,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("pause_stopwatch_button"),
                ) { Text("暂停") }
                TextButton(
                    onClick = vm::onResetStopwatch,
                    modifier = Modifier.fillMaxWidth().testTag("reset_stopwatch_button"),
                ) { Text("重置") }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::onStartStopwatch,
                        modifier = Modifier.weight(1f).height(56.dp).testTag("resume_stopwatch_button"),
                    ) { Text("继续") }
                    OutlinedButton(
                        onClick = vm::onResetStopwatch,
                        modifier = Modifier.weight(1f).height(56.dp).testTag("reset_stopwatch_button"),
                    ) { Text("重置") }
                }
            }
        }
    }
}

@Composable
private fun StatsDialog(snapshot: FocusSnapshot, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("番茄统计") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("今日完成 ${snapshot.todayCount} 番茄", fontWeight = FontWeight.Bold)
                Text("今日专注 ${formatDuration(snapshot.todaySeconds)}")
                HorizontalDivider()
                Text("最近 7 天", style = MaterialTheme.typography.labelLarge)
                snapshot.lastSevenDaysCounts.forEach { (date, count) ->
                    Text("${formatDate(date)} · ${count} 番茄")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        modifier = Modifier.testTag("stats_dialog"),
    )
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("focus_settings"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("专注设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

private fun formatClock(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = safe / 60
    val secs = safe % 60
    return "%02d:%02d".format(minutes, secs)
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0L) "${hours} 小时" else "${hours} 小时 ${rest} 分"
    } else {
        "${minutes} 分钟"
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("MM/dd")
private fun formatDate(date: LocalDate) = date.format(dateFormatter)
