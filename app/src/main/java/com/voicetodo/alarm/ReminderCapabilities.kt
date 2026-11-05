package com.voicetodo.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voicetodo.VoiceTodoApp

data class ReminderCapabilities(
    val canScheduleExactAlarm: Boolean,
    val canPostNotifications: Boolean,
    val canRecordAudio: Boolean,
    val apiLevel: Int = 0,
    val targetSdk: Int = 0,
    val exactAlarmAccessRequired: Boolean = false,
    val notificationPermissionRequired: Boolean = false,
    val notificationPermissionGranted: Boolean = canPostNotifications,
    val appNotificationsEnabled: Boolean = canPostNotifications,
    val reminderChannelEnabled: Boolean = canPostNotifications,
    val reminderChannelSoundEnabled: Boolean = false,
    val fullScreenAccessManaged: Boolean = false,
    val canUseFullScreenIntent: Boolean = false,
    val alarmVolumeAudible: Boolean = false,
    val doNotDisturbActive: Boolean = false,
) {
    val canSchedule: Boolean get() = canScheduleExactAlarm
    val notificationConditionsReady: Boolean get() = canPostNotifications
    val soundConditionsReady: Boolean get() = alarmVolumeAudible
}

internal data class ReminderCapabilitySignals(
    val exactAlarmAccessGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val reminderChannelEnabled: Boolean,
    val reminderChannelSoundEnabled: Boolean,
    val fullScreenPermissionDeclared: Boolean,
    val fullScreenAccessGranted: Boolean,
    val microphonePermissionGranted: Boolean,
    val alarmVolumeAudible: Boolean,
    val doNotDisturbActive: Boolean,
)

internal fun evaluateReminderCapabilities(
    apiLevel: Int,
    targetSdk: Int,
    signals: ReminderCapabilitySignals,
): ReminderCapabilities {
    val exactAlarmAccessRequired = apiLevel >= Build.VERSION_CODES.S
    val notificationPermissionRequired = apiLevel >= Build.VERSION_CODES.TIRAMISU
    val fullScreenAccessManaged = apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val canScheduleExactAlarm = !exactAlarmAccessRequired || signals.exactAlarmAccessGranted
    val notificationPermissionGranted =
        !notificationPermissionRequired || signals.notificationPermissionGranted
    val canPostNotifications = notificationPermissionGranted &&
        signals.appNotificationsEnabled && signals.reminderChannelEnabled
    val canUseFullScreenIntent = signals.fullScreenPermissionDeclared &&
        (!fullScreenAccessManaged || signals.fullScreenAccessGranted)
    return ReminderCapabilities(
        canScheduleExactAlarm = canScheduleExactAlarm,
        canPostNotifications = canPostNotifications,
        canRecordAudio = signals.microphonePermissionGranted,
        apiLevel = apiLevel,
        targetSdk = targetSdk,
        exactAlarmAccessRequired = exactAlarmAccessRequired,
        notificationPermissionRequired = notificationPermissionRequired,
        notificationPermissionGranted = notificationPermissionGranted,
        appNotificationsEnabled = signals.appNotificationsEnabled,
        reminderChannelEnabled = signals.reminderChannelEnabled,
        reminderChannelSoundEnabled = signals.reminderChannelSoundEnabled,
        fullScreenAccessManaged = fullScreenAccessManaged,
        canUseFullScreenIntent = canUseFullScreenIntent,
        alarmVolumeAudible = signals.alarmVolumeAudible,
        doNotDisturbActive = signals.doNotDisturbActive,
    )
}

fun readReminderCapabilities(context: Context): ReminderCapabilities {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val audioManager = context.getSystemService(AudioManager::class.java)
    val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.getNotificationChannel(VoiceTodoApp.CHANNEL_REMINDER)
    } else {
        null
    }
    val exactAlarmAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    } else {
        true
    }
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val fullScreenPermissionDeclared = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.USE_FULL_SCREEN_INTENT,
    ) == PackageManager.PERMISSION_GRANTED
    val fullScreenAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching { notificationManager.canUseFullScreenIntent() }.getOrDefault(false)
    } else {
        fullScreenPermissionDeclared
    }
    val interruptionFilter = runCatching { notificationManager.currentInterruptionFilter }
        .getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
    val signals = ReminderCapabilitySignals(
        exactAlarmAccessGranted = exactAlarmAccess,
        notificationPermissionGranted = notificationPermission,
        appNotificationsEnabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false),
        reminderChannelEnabled = channel?.importance != NotificationManager.IMPORTANCE_NONE,
        reminderChannelSoundEnabled = channel?.sound != null,
        fullScreenPermissionDeclared = fullScreenPermissionDeclared,
        fullScreenAccessGranted = fullScreenAccess,
        microphonePermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        alarmVolumeAudible = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM) > 0
        }.getOrDefault(false),
        doNotDisturbActive = interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
    )
    return evaluateReminderCapabilities(
        apiLevel = Build.VERSION.SDK_INT,
        targetSdk = context.applicationInfo.targetSdkVersion,
        signals = signals,
    )
}
