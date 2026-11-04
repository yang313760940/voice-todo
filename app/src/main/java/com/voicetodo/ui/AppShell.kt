package com.voicetodo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

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
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(MainTab.Todo.ordinal) }
    val selectedTab = MainTab.entries[selectedTabIndex]
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTabIndex = tab.ordinal },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        MainTab.Todo -> Icons.AutoMirrored.Filled.List
                                        MainTab.Quadrant -> Icons.Default.Menu
                                        MainTab.Pomodoro -> Icons.Default.Timer
                                        MainTab.Settings -> Icons.Default.Settings
                                    },
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(tab.testTag),
                        )
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
    }
}
