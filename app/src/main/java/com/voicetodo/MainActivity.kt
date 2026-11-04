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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicetodo.ui.PomodoroScreen
import com.voicetodo.ui.PomodoroViewModel
import com.voicetodo.ui.QuadrantScreen
import com.voicetodo.ui.SettingsScreen
import com.voicetodo.ui.SettingsViewModel
import com.voicetodo.ui.TodoScreen
import com.voicetodo.ui.TodoViewModel
import com.voicetodo.ui.VoiceTodoNavigationHost
import com.voicetodo.ui.VoiceTodoTheme

class MainActivity : ComponentActivity() {

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
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
                            onOpenNotificationSettings = ::openNotificationSettings,
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensurePermissions()
    }

    /** 最小权限检查：麦克风、通知（13+）；精确闹钟为特殊访问，引导到系统设置。 */
    private fun ensurePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPerms.launch(missing.toTypedArray())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, VoiceTodoApp.CHANNEL_REMINDER)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }
}

private typealias AlarmManager = android.app.AlarmManager
