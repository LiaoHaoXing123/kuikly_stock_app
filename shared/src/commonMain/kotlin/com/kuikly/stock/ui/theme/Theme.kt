package com.kuikly.stock.ui.theme

enum class ThemeMode(val key: String) {
    LIGHT("light"),
    DARK("dark");

    companion object {

        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

interface ThemeHost {

    val themeGeneration: Int
}

object ThemeManager {

    var mode: ThemeMode = ThemeMode.LIGHT
        private set

    var epoch: Int = 0
        private set

    val isDark: Boolean get() = mode == ThemeMode.DARK

    fun set(newMode: ThemeMode) {
        if (mode == newMode) return
        mode = newMode
        epoch++
    }

    fun toggle(): ThemeMode {
        set(if (isDark) ThemeMode.LIGHT else ThemeMode.DARK)
        return mode
    }

    fun restore(key: String?) {
        mode = ThemeMode.fromKey(key)
    }
}
