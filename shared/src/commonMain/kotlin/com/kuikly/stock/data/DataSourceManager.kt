package com.kuikly.stock.data

/**
 * 数据源模式管理（开发者选项）
 *
 * - OFFLINE（默认）：读取 assets 内嵌 JSON，零网络，真机无需与电脑同网
 * - ONLINE：调用局域网后端 ApiService；不可达时由 StockRepository 自动回退离线
 *
 * 模式用普通 var 存放（不做跨页响应式绑定），各页在加载数据时读取；
 * 持久化由首页 ChatMainPage 通过 SharedPreferencesModule 读写。
 */
object DataSourceManager {

    enum class Mode { OFFLINE, ONLINE }

    /** 当前模式，默认离线 */
    var mode: Mode = Mode.OFFLINE
        private set

    val isOnline: Boolean
        get() = mode == Mode.ONLINE

    fun setMode(m: Mode) {
        mode = m
    }

    /** SharedPreferences 持久化 key（与 ChatMainPage 共用） */
    const val PREFS_KEY = "data_source_mode"
}
