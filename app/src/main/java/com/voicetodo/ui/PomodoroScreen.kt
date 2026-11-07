package com.voicetodo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.domain.FocusPhase
import com.voicetodo.domain.FocusSession
import com.voicetodo.domain.FocusSessionState
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.FocusSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
            mode = state.mode,
            onSelectMode = vm::onSelectMode,
            onOpenSettings = vm::onOpenSettings,
            onShowStats = vm::onToggleStats,
        )
        Spacer(Modifier.height(24.dp))

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
                val phaseLabel = when {
                    session == null -> phaseName(state.selectedPhase)
                    session.state == FocusSessionState.Paused -> "${phaseName(session.phase)} · 已暂停"
                    else -> phaseName(session.phase)
                }
                PhaseHeader(
                    label = phaseLabel,
                    enabled = session == null,
                    showChevron = session == null,
                    onSelect = vm::onSelectPhase,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                TimeRing(
                    litFraction = if (totalSeconds > 0) remaining.toFloat() / totalSeconds else 1f,
                    display = formatClock(remaining),
                    modifier = Modifier.fillMaxWidth(0.82f).weight(1f).align(Alignment.CenterHorizontally),
                    tag = "focus_ring",
                )
                PomodoroControls(session, state.selectedPhase, state.busy, vm)
            }
            TimerMode.Stopwatch -> {
                val elapsed = vm.stopwatchSeconds(now)
                PhaseHeader(
                    label = "正计时",
                    enabled = false,
                    showChevron = false,
                    onSelect = {},
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                TimeRing(
                    litFraction = ((elapsed % 3600).toFloat() / 3600f).coerceIn(0f, 1f),
                    display = formatClock(elapsed),
                    modifier = Modifier.fillMaxWidth(0.82f).weight(1f).align(Alignment.CenterHorizontally),
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
    mode: TimerMode,
    onSelectMode: (TimerMode) -> Unit,
    onOpenSettings: () -> Unit,
    onShowStats: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedIconButton(
            onClick = onShowStats,
            modifier = Modifier.align(Alignment.CenterStart).testTag("stats_button"),
        ) {
            Icon(Icons.Outlined.Schedule, contentDescription = "统计")
        }
        TimerModeSelector(
            mode = mode,
            onSelect = onSelectMode,
            modifier = Modifier.align(Alignment.Center),
        )
        SettingsMenu(
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun SettingsMenu(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedIconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("settings_button"),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "设置选项")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("专注设置") },
                onClick = { expanded = false; onOpenSettings() },
                modifier = Modifier.testTag("menu_focus_settings"),
            )
        }
    }
}

@Composable
private fun TimerModeSelector(
    mode: TimerMode,
    onSelect: (TimerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TimerModeSegment(
            label = "番茄计时",
            selected = mode == TimerMode.Pomodoro,
            modifier = Modifier.testTag("mode_pomodoro"),
        ) { onSelect(TimerMode.Pomodoro) }
        TimerModeSegment(
            label = "正计时",
            selected = mode == TimerMode.Stopwatch,
            modifier = Modifier.testTag("mode_stopwatch"),
        ) { onSelect(TimerMode.Stopwatch) }
    }
}

@Composable
private fun TimerModeSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PhaseHeader(
    label: String,
    enabled: Boolean,
    showChevron: Boolean,
    onSelect: (FocusPhase) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("phase_selector"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (showChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "切换阶段",
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FocusPhase.values().forEach { phase ->
                DropdownMenuItem(
                    text = { Text(phaseName(phase)) },
                    onClick = {
                        expanded = false
                        onSelect(phase)
                    },
                    modifier = Modifier.testTag("phase_${phase.name}"),
                )
            }
        }
    }
}

@Composable
private fun TimeRing(
    litFraction: Float,
    display: String,
    modifier: Modifier = Modifier,
    tag: String,
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val active = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.testTag(tag), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = (min(size.width, size.height) - 12.dp.toPx()) / 2f
            val tickLength = 14.dp.toPx()
            val strokeWidth = 3.dp.toPx()
            val litTicks = (litFraction.coerceIn(0f, 1f) * TICK_COUNT).roundToInt()
            repeat(TICK_COUNT) { index ->
                val angle = Math.toRadians(index * 360.0 / TICK_COUNT - 90.0)
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                drawLine(
                    color = if (index < litTicks) active else track,
                    start = Offset(
                        center.x + dx * (radius - tickLength),
                        center.y + dy * (radius - tickLength),
                    ),
                    end = Offset(
                        center.x + dx * radius,
                        center.y + dy * radius,
                    ),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
        Text(display, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.SemiBold)
    }
}

private const val TICK_COUNT = 60

@Composable
private fun PomodoroControls(
    session: FocusSession?,
    selectedPhase: FocusPhase,
    busy: Boolean,
    vm: PomodoroViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            session == null -> PrimaryAction("开始", "start_button", !busy) {
                vm.onStart(selectedPhase)
            }
            session.state == FocusSessionState.EndedPendingConfirmation -> {
                PrimaryAction("确认完成", "confirm_ended_button", !busy, vm::onConfirmEnded)
            }
            session.state == FocusSessionState.Paused -> {
                PrimaryAction("继续", "resume_button", !busy, vm::onResume)
                SecondaryAction("提前结束", "end_early_button", !busy, vm::onEndEarly)
            }
            else -> {
                PrimaryAction("暂停", "pause_button", !busy, vm::onPause)
                SecondaryAction("提前结束", "end_early_button", !busy, vm::onEndEarly)
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    text: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        modifier = Modifier.width(160.dp).height(56.dp).testTag(tag),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SecondaryAction(
    text: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    ) {
        Text(text)
    }
}

@Composable
private fun StopwatchControls(state: PomodoroUiState, vm: PomodoroViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            !state.stopwatchRunning && state.stopwatchResumedAt == null && state.stopwatchElapsedSeconds == 0L -> {
                PrimaryAction("开始", "start_stopwatch_button", onClick = vm::onStartStopwatch)
            }
            state.stopwatchRunning -> {
                PrimaryAction("暂停", "pause_stopwatch_button", onClick = vm::onPauseStopwatch)
                SecondaryAction("重置", "reset_stopwatch_button", onClick = vm::onResetStopwatch)
            }
            else -> {
                PrimaryAction("继续", "resume_stopwatch_button", onClick = vm::onStartStopwatch)
                SecondaryAction("重置", "reset_stopwatch_button", onClick = vm::onResetStopwatch)
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
