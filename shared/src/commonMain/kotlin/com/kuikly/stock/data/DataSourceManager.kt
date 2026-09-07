// 数据源开关管理器。在线模式 AI 直连真实模型，离线模式回退本地模板回答。

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
