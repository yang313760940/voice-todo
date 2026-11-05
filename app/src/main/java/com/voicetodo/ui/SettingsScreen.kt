package com.voicetodo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.domain.FocusSettings
import com.voicetodo.settings.AppPreferences
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference

private enum class SettingsDestination {
    Root,
    Permissions,
    Data,
    Trash,
    About,
}

enum class ReminderSettingsTarget {
    NotificationChannel,
    ExactAlarm,
    FullScreen,
    AppDetails,
    Sound,
}

@Composable
fun SettingsScreen(
    settingsVm: SettingsViewModel,
    todoVm: TodoViewModel,
    appVersion: String,
    onOpenReminderSettings: (ReminderSettingsTarget) -> Unit,
) {
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val todoState by todoVm.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.Root) }

    LifecycleResumeEffect(settingsVm) {
        settingsVm.refreshCapabilities()
        onPauseOrDispose { }
    }

    todoState.aiSettingsForm?.let { form ->
        BackHandler { todoVm.onCloseAiSettings() }
        AiSettingsScreen(todoVm, todoState, form)
        return
    }

    BackHandler(enabled = destination != SettingsDestination.Root) {
        destination = SettingsDestination.Root
    }

    when (destination) {
        SettingsDestination.Root -> MySettingsContent(
            preferences = settingsState.preferences,
            focusSettings = settingsState.focusSettings,
            capabilities = settingsState.capabilities,
            aiConfigured = todoState.aiConfigured,
            busy = settingsState.busy,
            message = settingsState.message,
            onThemeChange = settingsVm::setTheme,
            onFontSizeChange = settingsVm::setFontSize,
            onFocusSettingsChange = settingsVm::updateFocusSettings,
            onOpenVoiceAi = todoVm::onOpenAiSettings,
            onOpenNotificationSettings = {
                onOpenReminderSettings(ReminderSettingsTarget.NotificationChannel)
            },
            onOpenPermissions = { destination = SettingsDestination.Permissions },
            onOpenData = { destination = SettingsDestination.Data },
            onOpenTrash = { destination = SettingsDestination.Trash },
            onOpenAbout = { destination = SettingsDestination.About },
        )
        SettingsDestination.Permissions -> PermissionsGuidanceScreen(
            capabilities = settingsState.capabilities,
            onOpenSettings = onOpenReminderSettings,
            onBack = { destination = SettingsDestination.Root },
        )
        SettingsDestination.Data -> PendingFeatureScreen(
            title = "数据管理",
            body = "备份导出、预览替换导入与恢复副本将由 #18 交付。此入口已经保留。",
            testTag = "data_management_screen",
            onBack = { destination = SettingsDestination.Root },
        )
        SettingsDestination.Trash -> PendingFeatureScreen(
            title = "回收站",
            body = "30 天回收站、恢复与重复事项删除范围将由 #20 交付。此入口已经保留。",
            testTag = "trash_screen",
            onBack = { destination = SettingsDestination.Root },
        )
        SettingsDestination.About -> AboutScreen(
            appVersion = appVersion,
            onBack = { destination = SettingsDestination.Root },
        )
    }
}

@Composable
internal fun MySettingsContent(
    preferences: AppPreferences,
    focusSettings: FocusSettings,
    capabilities: ReminderCapabilities?,
    aiConfigured: Boolean,
    busy: Boolean,
    message: String?,
    onThemeChange: (ThemePreference) -> Unit,
    onFontSizeChange: (FontSizePreference) -> Unit,
    onFocusSettingsChange: (FocusSettings) -> Unit,
    onOpenVoiceAi: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenData: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("my_screen"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "我的",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "本机使用，无需账号。事项、偏好和专注记录保存在设备上。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            SettingsSection("提醒设置", "settings_reminder") {
                Text(
                    text = "通知、声音与锁屏呈现由系统通知频道管理。应用不会擅自修改勿扰或音量。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier.testTag("open_notification_settings"),
                ) {
                    Text("打开系统通知与声音设置")
                }
            }
        }
        item {
            SettingsSection("番茄设置", "settings_pomodoro") {
                Text(
                    text = "修改后立即保存；当前正在运行的会话保持原时长。",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberStepper(
                    label = "专注",
                    value = focusSettings.focusMinutes,
                    unit = "分钟",
                    range = 1..180,
                    enabled = !busy,
                    tag = "focus_minutes",
                    onChange = { onFocusSettingsChange(focusSettings.copy(focusMinutes = it)) },
                )
                NumberStepper(
                    label = "短休息",
                    value = focusSettings.shortBreakMinutes,
                    unit = "分钟",
                    range = 1..60,
                    enabled = !busy,
                    tag = "short_break_minutes",
                    onChange = { onFocusSettingsChange(focusSettings.copy(shortBreakMinutes = it)) },
                )
                NumberStepper(
                    label = "长休息",
                    value = focusSettings.longBreakMinutes,
                    unit = "分钟",
                    range = 1..120,
                    enabled = !busy,
                    tag = "long_break_minutes",
                    onChange = { onFocusSettingsChange(focusSettings.copy(longBreakMinutes = it)) },
                )
                NumberStepper(
                    label = "长休息间隔",
                    value = focusSettings.longBreakEvery,
                    unit = "次专注",
                    range = 1..20,
                    enabled = !busy,
                    tag = "long_break_every",
                    onChange = { onFocusSettingsChange(focusSettings.copy(longBreakEvery = it)) },
                )
            }
        }
        item {
            SettingsSection("外观", "settings_appearance") {
                Text("主题", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(ThemePreference.System, "跟随系统", preferences.theme, onThemeChange)
                    ThemeChip(ThemePreference.Light, "浅色", preferences.theme, onThemeChange)
                    ThemeChip(ThemePreference.Dark, "深色", preferences.theme, onThemeChange)
                }
                Text("字体大小", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontChip(FontSizePreference.Small, "小", preferences.fontSize, onFontSizeChange)
                    FontChip(FontSizePreference.Default, "标准", preferences.fontSize, onFontSizeChange)
                    FontChip(FontSizePreference.Large, "大", preferences.fontSize, onFontSizeChange)
                }
            }
        }
        item {
            SettingsSection("语音与 AI", "settings_voice_ai") {
                Text(
                    text = if (aiConfigured) {
                        "AI 服务已配置。语音识别仍在本机离线完成。"
                    } else {
                        "AI 服务未配置；离线语音与手动创建可正常使用。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ai_settings_status"),
                )
                TextButton(
                    onClick = onOpenVoiceAi,
                    modifier = Modifier.testTag("open_ai_settings"),
                ) {
                    Text("高级设置")
                }
            }
        }
        item {
            SettingsRow(
                title = "权限与提醒检查",
                detail = capabilitySummary(capabilities),
                tag = "settings_permissions",
                onClick = onOpenPermissions,
            )
        }
        item {
            SettingsRow(
                title = "数据管理",
                detail = "备份与恢复入口（由 #18 交付）",
                tag = "settings_data",
                onClick = onOpenData,
            )
        }
        item {
            SettingsRow(
                title = "回收站",
                detail = "30 天删除恢复（由 #20 交付）",
                tag = "settings_trash",
                onClick = onOpenTrash,
            )
        }
        item {
            SettingsRow(
                title = "关于",
                detail = "版本与隐私说明",
                tag = "settings_about",
                onClick = onOpenAbout,
            )
        }
        message?.let { text ->
            item {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("settings_message"),
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    tag: String,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    detail: String,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun ThemeChip(
    value: ThemePreference,
    label: String,
    selected: ThemePreference,
    onChange: (ThemePreference) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onChange(value) },
        label = { Text(label) },
        modifier = Modifier.testTag("theme_${value.name.lowercase()}"),
    )
}

@Composable
private fun FontChip(
    value: FontSizePreference,
    label: String,
    selected: FontSizePreference,
    onChange: (FontSizePreference) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onChange(value) },
        label = { Text(label) },
        modifier = Modifier.testTag("font_${value.name.lowercase()}"),
    )
}

@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    unit: String,
    range: IntRange,
    enabled: Boolean,
    tag: String,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label：$value $unit", modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = { if (value > range.first) onChange(value - 1) },
            enabled = enabled && value > range.first,
            modifier = Modifier.testTag("${tag}_minus"),
        ) {
            Text("−")
        }
        OutlinedButton(
            onClick = { if (value < range.last) onChange(value + 1) },
            enabled = enabled && value < range.last,
            modifier = Modifier.testTag("${tag}_plus"),
        ) {
            Text("+")
        }
    }
}

@Composable
internal fun PermissionsGuidanceScreen(
    capabilities: ReminderCapabilities?,
    onOpenSettings: (ReminderSettingsTarget) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("permissions_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("权限与提醒检查", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (capabilities == null) {
                    "暂时无法读取系统能力；可以返回后重试，事项数据不会受影响。"
                } else {
                    "系统 API ${capabilities.apiLevel}，" +
                        "应用 target SDK ${capabilities.targetSdk}。返回应用后会重新读取真实状态。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            SettingsSection("提醒链路", "reminder_readiness") {
                CapabilityLine("事项保存", "可用", true, "保存成功不代表已经排入系统。")
                CapabilityLine(
                    "系统调度",
                    readyText(capabilities?.canScheduleExactAlarm),
                    capabilities?.canScheduleExactAlarm,
                    "只有这里就绪，未来提醒才会登记为系统精确闹钟。",
                )
                CapabilityLine(
                    "通知可见条件",
                    readyText(capabilities?.notificationConditionsReady),
                    capabilities?.notificationConditionsReady,
                    "这里只表示权限、应用开关和频道均允许；实际到点展示仍需设备验证。",
                )
            }
        }
        item {
            SettingsSection("系统权限与开关", "reminder_permissions") {
                CapabilityLine(
                    "通知运行时权限",
                    when {
                        capabilities == null -> "无法读取"
                        !capabilities.notificationPermissionRequired -> "此版本无需单独授权"
                        capabilities.notificationPermissionGranted -> "已授权"
                        else -> "未授权"
                    },
                    capabilities?.notificationPermissionGranted,
                    "Android 13 起需要授权；撤回后提醒仍保存，但通知不可见。",
                    "打开应用设置",
                ) { onOpenSettings(ReminderSettingsTarget.AppDetails) }
                CapabilityLine(
                    "应用通知总开关",
                    readyText(capabilities?.appNotificationsEnabled),
                    capabilities?.appNotificationsEnabled,
                    "系统可在应用级别关闭全部通知。",
                    "打开通知设置",
                ) { onOpenSettings(ReminderSettingsTarget.NotificationChannel) }
                CapabilityLine(
                    "提醒通知频道",
                    readyText(capabilities?.reminderChannelEnabled),
                    capabilities?.reminderChannelEnabled,
                    "频道关闭时不会出现提醒通知。",
                    "打开频道设置",
                ) { onOpenSettings(ReminderSettingsTarget.NotificationChannel) }
                CapabilityLine(
                    "精确闹钟",
                    when {
                        capabilities == null -> "无法读取"
                        !capabilities.exactAlarmAccessRequired -> "此版本无需特殊访问"
                        capabilities.canScheduleExactAlarm -> "已允许"
                        else -> "未允许"
                    },
                    capabilities?.canScheduleExactAlarm,
                    "未允许时事项照常保存，但提醒标记为未就绪。",
                    if (capabilities?.exactAlarmAccessRequired == true) "打开精确闹钟设置" else null,
                ) { onOpenSettings(ReminderSettingsTarget.ExactAlarm) }
                CapabilityLine(
                    "麦克风",
                    readyText(capabilities?.canRecordAudio),
                    capabilities?.canRecordAudio,
                    "只影响离线语音输入，不影响手动创建事项。",
                    "打开应用设置",
                ) { onOpenSettings(ReminderSettingsTarget.AppDetails) }
            }
        }
        item {
            SettingsSection("锁屏与声音", "reminder_presentation") {
                CapabilityLine(
                    "全屏提醒入口",
                    when {
                        capabilities == null -> "无法读取"
                        capabilities.canUseFullScreenIntent -> "可用"
                        else -> "受系统限制"
                    },
                    capabilities?.canUseFullScreenIntent,
                    if (capabilities?.canUseFullScreenIntent == false) {
                        "到点仍保留系统允许的通知与响铃；点通知可进入提醒界面。"
                    } else {
                        "锁屏正文默认隐藏，解锁后才显示事项内容。"
                    },
                    if (capabilities?.fullScreenAccessManaged == true) "打开全屏提醒设置" else null,
                ) { onOpenSettings(ReminderSettingsTarget.FullScreen) }
                CapabilityLine(
                    "通知频道声音",
                    readyText(capabilities?.reminderChannelSoundEnabled),
                    capabilities?.reminderChannelSoundEnabled,
                    "应用不会擅自修改频道声音。",
                    "打开频道设置",
                ) { onOpenSettings(ReminderSettingsTarget.NotificationChannel) }
                CapabilityLine(
                    "闹钟音量",
                    readyText(capabilities?.alarmVolumeAudible),
                    capabilities?.alarmVolumeAudible,
                    "音量为零时可能无声；应用不会擅自提高音量。",
                    "打开声音设置",
                ) { onOpenSettings(ReminderSettingsTarget.Sound) }
                CapabilityLine(
                    "勿扰模式",
                    when (capabilities?.doNotDisturbActive) {
                        true -> "已开启，遵循系统规则"
                        false -> "未开启"
                        null -> "无法读取"
                    },
                    capabilities?.doNotDisturbActive?.not(),
                    "应用不会绕过或擅自修改勿扰设置。",
                )
            }
        }
        item {
            Text(
                "事项保存、系统调度、通知可见和实际发声是不同状态，本页分别展示，不用单一“成功”代替。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onBack, modifier = Modifier.testTag("permissions_back")) {
                Text("返回我的")
            }
        }
    }
}

@Composable
private fun CapabilityLine(
    label: String,
    status: String,
    ready: Boolean?,
    detail: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                status,
                color = when (ready) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
        actionLabel?.let {
            TextButton(onClick = onAction, modifier = Modifier.testTag("capability_action_${label}")) {
                Text(it)
            }
        }
    }
}

private fun readyText(ready: Boolean?): String = when (ready) {
    true -> "已具备"
    false -> "未具备"
    null -> "无法读取"
}

@Composable
private fun PendingFeatureScreen(
    title: String,
    body: String,
    testTag: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider()
        Text(body)
        Text(
            "该分组入口已纳入四个底部页面；具体数据操作不会在依赖票完成前伪装为可用。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onBack, modifier = Modifier.testTag("${testTag}_back")) {
            Text("返回我的")
        }
    }
}

@Composable
internal fun AboutScreen(
    appVersion: String,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("about_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("关于语音提醒", style = MaterialTheme.typography.headlineSmall)
            Text(
                "版本 $appVersion",
                modifier = Modifier.testTag("about_version"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("隐私说明", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "事项、专注记录、偏好和回收站数据保存在本机。语音识别在本机离线完成；" +
                            "只有你主动选择在线 AI 整理时，当前文字及必要日期、时间和时区才会发送到" +
                            "你配置的 AI 服务。录音、历史事项、API 密钥和权限授权不会作为 AI 上下文发送。" +
                            "API 密钥使用 Android Keystore 加密保存，不写入日志或业务备份。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("about_privacy"),
                    )
                }
            }
        }
        item {
            Button(onClick = onBack, modifier = Modifier.testTag("about_back")) {
                Text("返回我的")
            }
        }
    }
}

internal fun capabilitySummary(capabilities: ReminderCapabilities?): String {
    if (capabilities == null) return "无法读取系统权限状态"
    return listOf(
        if (capabilities.canScheduleExactAlarm) "精确闹钟已具备" else "精确闹钟未具备",
        if (capabilities.notificationConditionsReady) "通知条件已具备" else "通知条件未具备",
        if (capabilities.canUseFullScreenIntent) "全屏入口可用" else "全屏入口受限",
        if (capabilities.canRecordAudio) "麦克风已具备" else "麦克风未具备",
    ).joinToString(" · ")
}
