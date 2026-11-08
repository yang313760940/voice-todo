package com.voicetodo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val BottomNavBlue = Color(0xFF007AFF)

internal enum class MainTab(
    val label: String,
    val testTag: String,
) {
    Todo("待办", "nav_todo"),
    Quadrant("四象限", "nav_quadrant"),
    Pomodoro("番茄", "nav_pomodoro"),
    Settings("我的", "nav_settings"),
}

@Composable
internal fun VoiceTodoNavigationHost(
    todoContent: @Composable () -> Unit,
    quadrantContent: @Composable () -> Unit,
    pomodoroContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    todoHasUnsavedContent: () -> Boolean = { false },
    onTodoTabLeaveAttempt: () -> Unit = {},
    onContinueTodoEditing: () -> Unit = {},
    onDiscardTodoDraft: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onVoiceLongClick: () -> Unit = {},
    showVoice: Boolean = true,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(MainTab.Todo.ordinal) }
    var pendingTab by remember { mutableStateOf<MainTab?>(null) }
    val selectedTab = MainTab.entries[selectedTabIndex]

    fun requestTab(tab: MainTab) {
        if (tab == selectedTab) return
        if (selectedTab == MainTab.Todo) {
            onTodoTabLeaveAttempt()
            if (todoHasUnsavedContent()) {
                pendingTab = tab
                return
            }
        }
        selectedTabIndex = tab.ordinal
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NavTabItem(MainTab.Todo, selectedTab == MainTab.Todo, Modifier.weight(1f)) {
                                requestTab(MainTab.Todo)
                            }
                            NavTabItem(MainTab.Quadrant, selectedTab == MainTab.Quadrant, Modifier.weight(1f)) {
                                requestTab(MainTab.Quadrant)
                            }
                            Spacer(Modifier.weight(1f))
                            NavTabItem(MainTab.Pomodoro, selectedTab == MainTab.Pomodoro, Modifier.weight(1f)) {
                                requestTab(MainTab.Pomodoro)
                            }
                            NavTabItem(MainTab.Settings, selectedTab == MainTab.Settings, Modifier.weight(1f)) {
                                requestTab(MainTab.Settings)
                            }
                        }
                    }
                },
            ) { contentPadding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                ) {
                    when (selectedTab) {
                        MainTab.Todo -> todoContent()
                        MainTab.Quadrant -> quadrantContent()
                        MainTab.Pomodoro -> pomodoroContent()
                        MainTab.Settings -> settingsContent()
                    }
                }
            }
            if (selectedTab == MainTab.Todo && showVoice) {
                CenterVoiceButton(
                    onClick = onVoiceClick,
                    onLongClick = onVoiceLongClick,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-28).dp),
                )
            }
        }
    }
    pendingTab?.let { target ->
        AlertDialog(
            onDismissRequest = {
                pendingTab = null
                onContinueTodoEditing()
            },
            title = { Text("放弃本次语音内容？") },
            text = { Text("切换页面会离开尚未保存的语音文字或草稿。继续编辑会保留内容并改为手动保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDiscardTodoDraft()
                        selectedTabIndex = target.ordinal
                        pendingTab = null
                    },
                    modifier = Modifier.testTag("nav_discard_voice"),
                ) { Text("放弃并切换") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingTab = null
                        onContinueTodoEditing()
                    },
                    modifier = Modifier.testTag("nav_continue_voice"),
                ) { Text("继续编辑") }
            },
            modifier = Modifier.testTag("nav_voice_discard_dialog"),
        )
    }
}

@Composable
private fun NavTabItem(tab: MainTab, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (selected) {
        BottomNavBlue
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag(tab.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = when (tab) {
                MainTab.Todo -> Icons.AutoMirrored.Filled.List
                MainTab.Quadrant -> Icons.Default.Menu
                MainTab.Pomodoro -> Icons.Default.Timer
                MainTab.Settings -> Icons.Default.Settings
            },
            contentDescription = tab.label,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Text(tab.label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CenterVoiceButton(onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .size(72.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("voice_mic_button"),
        shape = CircleShape,
        color = BottomNavBlue,
        contentColor = Color.White,
        shadowElevation = 6.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "语音添加；长按打开完整手动表单",
                modifier = Modifier.size(30.dp),
            )
            Text("语音", style = MaterialTheme.typography.labelMedium)
        }
    }
}
