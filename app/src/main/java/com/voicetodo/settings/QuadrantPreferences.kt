package com.voicetodo.settings

import android.content.Context
import android.content.SharedPreferences
import com.voicetodo.domain.Quadrant

data class QuadrantPreferences(
    val order: List<Quadrant> = Quadrant.entries,
    val showCompleted: Boolean = false,
)

class QuadrantPreferencesStore(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun load(): QuadrantPreferences = QuadrantPreferences(
        order = parseOrder(preferences.getString(KEY_ORDER, null)),
        showCompleted = preferences.getBoolean(KEY_SHOW_COMPLETED, false),
    )

    fun saveOrder(order: List<Quadrant>): QuadrantPreferences {
        require(order.size == Quadrant.entries.size && order.toSet() == Quadrant.entries.toSet()) {
            "象限排列必须包含四个不同象限"
        }
        check(
            preferences.edit().putString(KEY_ORDER, order.joinToString(",") { it.name }).commit(),
        ) { "象限排列保存失败" }
        return load()
    }

    fun saveShowCompleted(showCompleted: Boolean): QuadrantPreferences {
        check(
            preferences.edit().putBoolean(KEY_SHOW_COMPLETED, showCompleted).commit(),
        ) { "已完成显示偏好保存失败" }
        return load()
    }

    private fun parseOrder(value: String?): List<Quadrant> {
        val parsed = value?.split(',')?.mapNotNull { name ->
            runCatching { Quadrant.valueOf(name) }.getOrNull()
        }.orEmpty()
        return parsed.takeIf {
            it.size == Quadrant.entries.size && it.toSet() == Quadrant.entries.toSet()
        } ?: Quadrant.entries
    }

    companion object {
        const val PREFERENCES_NAME = "quadrant_preferences"
        const val KEY_ORDER = "order"
        const val KEY_SHOW_COMPLETED = "show_completed"
    }
}
