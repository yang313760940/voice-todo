package com.voicetodo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import com.voicetodo.settings.AppPreferences
import com.voicetodo.settings.FontSizePreference
import com.voicetodo.settings.ThemePreference

@Composable
fun VoiceTodoTheme(
    preferences: AppPreferences,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (preferences.theme) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme(),
        typography = scaledTypography(preferences.fontSize),
        content = content,
    )
}

internal fun scaledTypography(fontSize: FontSizePreference): Typography {
    val scale = fontSize.scale
    fun TextStyle.scaled(): TextStyle = copy(fontSize = this.fontSize * scale)
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}
