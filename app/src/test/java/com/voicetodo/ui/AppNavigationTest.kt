package com.voicetodo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
class AppNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourTabsSwitchAndTodoDraftStateSurvives() {
        compose.setContent {
            MaterialTheme {
                var draft by rememberSaveable { mutableStateOf("") }
                VoiceTodoNavigationHost(
                    todoContent = {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.testTag("todo_nav_state"),
                        )
                    },
                    quadrantContent = { Text("四象限页", Modifier.testTag("quadrant_page")) },
                    pomodoroContent = { Text("番茄页", Modifier.testTag("pomodoro_page")) },
                    settingsContent = { Text("我的页", Modifier.testTag("my_page")) },
                )
            }
        }

        compose.onNodeWithTag("todo_nav_state").performTextInput("保留草稿")
        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("quadrant_page").assertIsDisplayed()
        compose.onNodeWithTag("nav_pomodoro").performClick()
        compose.onNodeWithTag("pomodoro_page").assertIsDisplayed()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("my_page").assertIsDisplayed()
        compose.onNodeWithTag("nav_todo").performClick()
        compose.onNodeWithTag("todo_nav_state").assertTextContains("保留草稿")
    }

    @Test fun switchingTabsWithUnsavedVoiceContentRequiresAChoice() {
        var unsaved by mutableStateOf(true)
        var continued = 0
        var discarded = 0
        var leaveAttempts = 0
        compose.setContent {
            MaterialTheme {
                VoiceTodoNavigationHost(
                    todoContent = { Text("待办页", Modifier.testTag("todo_page")) },
                    quadrantContent = { Text("四象限页", Modifier.testTag("quadrant_page")) },
                    pomodoroContent = { Text("番茄页", Modifier.testTag("pomodoro_page")) },
                    settingsContent = { Text("我的页", Modifier.testTag("my_page")) },
                    todoHasUnsavedContent = { unsaved },
                    onTodoTabLeaveAttempt = { leaveAttempts++ },
                    onContinueTodoEditing = { continued++ },
                    onDiscardTodoDraft = {
                        discarded++
                        unsaved = false
                    },
                )
            }
        }

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("nav_voice_discard_dialog").assertIsDisplayed()
        compose.onNodeWithTag("quadrant_page").assertDoesNotExist()
        assert(leaveAttempts == 1)

        compose.onNodeWithTag("nav_continue_voice").performClick()
        compose.onNodeWithTag("todo_page").assertIsDisplayed()
        assert(continued == 1)

        compose.onNodeWithTag("nav_quadrant").performClick()
        compose.onNodeWithTag("nav_discard_voice").performClick()
        compose.onNodeWithTag("quadrant_page").assertIsDisplayed()
        assert(discarded == 1)
        assert(leaveAttempts == 2)
    }
}
