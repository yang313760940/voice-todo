package com.voicetodo.settings

import android.content.Context
import android.content.SharedPreferences

enum class ThemePreference {
    System,
    Light,
    Dark,
}

enum class FontSizePreference(val scale: Float) {
    Small(0.9f),
    Default(1.0f),
    Large(1.2f),
}

data class AppPreferences(
    val theme: ThemePreference = ThemePreference.System,
    val fontSize: FontSizePreference = FontSizePreference.Default,
)

class AppPreferencesStore(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun load(): AppPreferences = AppPreferences(
        theme = enumValueOrDefault(preferences.getString(KEY_THEME, null), ThemePreference.System),
        fontSize = enumValueOrDefault(
            preferences.getString(KEY_FONT_SIZE, null),
            FontSizePreference.Default,
        ),
    )

    fun saveTheme(theme: ThemePreference): AppPreferences {
        check(
            preferences.edit().putString(KEY_THEME, theme.name).commit(),
        ) { "外观设置保存失败" }
        return load()
    }

    fun saveFontSize(fontSize: FontSizePreference): AppPreferences {
        check(
            preferences.edit().putString(KEY_FONT_SIZE, fontSize.name).commit(),
        ) { "字体大小保存失败" }
        return load()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        fallback: T,
    ): T = value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        const val PREFERENCES_NAME = "app_preferences"
        const val KEY_THEME = "theme"
        const val KEY_FONT_SIZE = "font_size"
    }
}
