package com.voicetodo

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicetodo.ui.PomodoroScreen
import com.voicetodo.ui.PomodoroViewModel
import com.voicetodo.ui.QuadrantScreen
import com.voicetodo.ui.ReminderSettingsTarget
import com.voicetodo.ui.SettingsScreen
import com.voicetodo.ui.SettingsViewModel
import com.voicetodo.ui.TodoScreen
import com.voicetodo.ui.TodoViewModel
import com.voicetodo.ui.VoiceTodoNavigationHost
import com.voicetodo.ui.VoiceTodoTheme

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            settingsViewModel.refreshCapabilities()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
            val todoViewModel: TodoViewModel = viewModel()
            val pomodoroViewModel: PomodoroViewModel = viewModel()
            val appVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty().ifBlank { "未知" }

            VoiceTodoTheme(settingsState.preferences) {
                VoiceTodoNavigationHost(
                    todoContent = { TodoScreen(todoViewModel) },
                    quadrantContent = { QuadrantScreen(todoViewModel) },
                    pomodoroContent = { PomodoroScreen(pomodoroViewModel) },
                    settingsContent = {
                        SettingsScreen(
                            settingsVm = settingsViewModel,
                            todoVm = todoViewModel,
                            appVersion = appVersion,
                            onOpenReminderSettings = ::openReminderSettings,
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settingsViewModel.refreshCapabilities()
        (application as VoiceTodoApp).requestReminderReconcile()
    }

    /** 首次进入请求运行时权限；特殊访问由提醒能力页按用户操作打开。 */
    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPerms.launch(missing.toTypedArray())
    }

    private fun openReminderSettings(target: ReminderSettingsTarget) {
        val packageUri = Uri.parse("package:$packageName")
        val intent = when (target) {
            ReminderSettingsTarget.NotificationChannel ->
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, VoiceTodoApp.CHANNEL_REMINDER)
            ReminderSettingsTarget.ExactAlarm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(packageUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
            }
            ReminderSettingsTarget.FullScreen ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(packageUri)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
                }
            ReminderSettingsTarget.AppDetails ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
            ReminderSettingsTarget.Sound -> Intent(Settings.ACTION_SOUND_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri))
                }
            }
    }
}
