package com.kuikly.stock.data

object DataSourceManager {

    enum class Mode { OFFLINE, ONLINE }

    var mode: Mode = Mode.ONLINE
    private set

    val isOnline: Boolean
    get() = mode == Mode.ONLINE

    fun setMode(m: Mode) {
        mode = m
    }

    const val PREFS_KEY = "data_source_mode"
}
