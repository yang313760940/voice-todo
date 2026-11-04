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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicetodo.ui.PomodoroScreen
import com.voicetodo.ui.PomodoroViewModel
import com.voicetodo.ui.TodoScreen
import com.voicetodo.ui.TodoViewModel

class MainActivity : ComponentActivity() {

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            MaterialTheme {
                Surface {
                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize()) {
                        when (selectedTab) {
                            0 -> {
                                val vm: TodoViewModel = viewModel()
                                TodoScreen(vm)
                            }
                            1 -> {
                                val vm: PomodoroViewModel = viewModel()
                                PomodoroScreen(vm)
                            }
                        }
                        NavigationBar {
                            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.List, contentDescription = null) }, label = { Text("待办") })
                            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Timer, contentDescription = null) }, label = { Text("番茄") })
                        }
                    }
                }
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
}

private typealias AlarmManager = android.app.AlarmManager
