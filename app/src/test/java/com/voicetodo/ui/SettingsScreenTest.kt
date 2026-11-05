package com.voicetodo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.voicetodo.alarm.ReminderCapabilities
import com.voicetodo.domain.FocusSettings
import com.voicetodo.domain.TrashedItem
import com.voicetodo.domain.TodoItem
import com.voicetodo.settings.AppPreferences
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h1600dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun myGroupsAreReachableAndEditsApplyImmediately() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                var preferences by remember { mutableStateOf(AppPreferences()) }
                var focusSettings by remember { mutableStateOf(FocusSettings()) }
                MySettingsContent(
                    preferences = preferences,
                    focusSettings = focusSettings,
                    capabilities = ReminderCapabilities(
                        canScheduleExactAlarm = true,
                        canPostNotifications = false,
                        canRecordAudio = true,
                    ),
                    aiConfigured = true,
                    busy = false,
                    message = null,
                    onThemeChange = { preferences = preferences.copy(theme = it) },
                    onFontSizeChange = { preferences = preferences.copy(fontSize = it) },
                    onFocusSettingsChange = { focusSettings = it },
                    onOpenVoiceAi = { opened = "ai" },
                    onOpenNotificationSettings = { opened = "notifications" },
                    onOpenPermissions = { opened = "permissions" },
                    onOpenData = { opened = "data" },
                    onOpenTrash = { opened = "trash" },
                    onOpenAbout = { opened = "about" },
                )
            }
        }

        compose.onNodeWithTag("settings_reminder").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_pomodoro").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_appearance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_voice_ai").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_permissions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_data").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_trash").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_about").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("theme_dark").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("font_large").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("focus_minutes_plus").performScrollTo().performClick()
        compose.onNodeWithText("专注：26 分钟").assertIsDisplayed()

        compose.onNodeWithTag("open_ai_settings").performScrollTo().performClick()
        assertEquals("ai", opened)
        compose.onNodeWithTag("settings_permissions").performScrollTo().performClick()
        assertEquals("permissions", opened)
        compose.onNodeWithTag("settings_data").performScrollTo().performClick()
        assertEquals("data", opened)
        compose.onNodeWithTag("settings_trash").performScrollTo().performClick()
        assertEquals("trash", opened)
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        assertEquals("about", opened)
    }

    @Test fun trashPageShowsRetentionAndSupportsRestoreAndConfirmedPermanentDelete() {
        val deletedAt = Instant.parse("2026-11-05T01:00:00Z")
        val item = TodoItem(
            title = "误删事项",
            createdAt = deletedAt.minusSeconds(60),
            deletedAt = deletedAt,
        )
        val trashed = TrashedItem(
            item = item,
            deletedAt = deletedAt,
            expiresAt = deletedAt.plus(Duration.ofDays(30)),
            daysRemaining = 30,
        )
        var restored = ""
        var deleted = ""
        compose.setContent {
            MaterialTheme {
                TrashScreen(
                    state = TrashUiState(items = listOf(trashed)),
                    onBack = {},
                    onRestore = { restored = it },
                    onPermanentlyDelete = { deleted = it },
                    onRefresh = {},
                )
            }
        }

        compose.onNodeWithTag("trash_item_0").assertIsDisplayed()
        compose.onNodeWithTag("trash_remaining_0").assertIsDisplayed()
        compose.onNodeWithText("误删事项").assertIsDisplayed()
        compose.onNodeWithTag("trash_restore_0").performClick()
        assertEquals(item.id, restored)
        compose.onNodeWithTag("trash_delete_0").performClick()
        compose.onNodeWithTag("trash_delete_dialog").assertIsDisplayed()
        compose.onNodeWithTag("trash_confirm_delete").performClick()
        assertEquals(item.id, deleted)
    }

    @Test fun aboutPageStatesVersionDataBoundaryAndAiSendingScope() {
        compose.setContent {
            MaterialTheme {
                AboutScreen(appVersion = "0.1.0-test", onBack = {})
            }
        }
        compose.onNodeWithTag("about_version").assertIsDisplayed()
        compose.onNodeWithText("版本 0.1.0-test").assertIsDisplayed()
        compose.onNodeWithTag("about_privacy").assertIsDisplayed()
        compose.onNodeWithText("只有你主动选择在线 AI 整理时", substring = true)
            .assertIsDisplayed()
    }

    @Test fun reminderCheckSeparatesSaveScheduleVisibilityAndSettingsActions() {
        var opened: ReminderSettingsTarget? = null
        compose.setContent {
            MaterialTheme {
                PermissionsGuidanceScreen(
                    capabilities = ReminderCapabilities(
                        canScheduleExactAlarm = false,
                        canPostNotifications = false,
                        canRecordAudio = false,
                        apiLevel = 34,
                        targetSdk = 34,
                        exactAlarmAccessRequired = true,
                        notificationPermissionRequired = true,
                        notificationPermissionGranted = false,
                        appNotificationsEnabled = true,
                        reminderChannelEnabled = true,
                        reminderChannelSoundEnabled = false,
                        fullScreenAccessManaged = true,
                        canUseFullScreenIntent = false,
                        alarmVolumeAudible = false,
                        doNotDisturbActive = true,
                    ),
                    onOpenSettings = { opened = it },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("事项保存").assertIsDisplayed()
        compose.onNodeWithText("系统调度").assertIsDisplayed()
        compose.onNodeWithText("通知可见条件").assertIsDisplayed()
        compose.onNodeWithTag("reminder_recovery_triggers").assertIsDisplayed()
        compose.onNodeWithTag("force_stop_boundary").assertIsDisplayed()
        compose.onNodeWithText("提醒通知频道").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("全屏提醒入口").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("到点仍保留系统允许的通知与响铃", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("打开全屏提醒设置").performScrollTo().performClick()
        assertEquals(ReminderSettingsTarget.FullScreen, opened)
    }
}
