package com.kuikly.stock.data

/**
 * AI 开关（开发者选项）
 *
 * 方案 B 下数据全部来自本地 SQLite（[StockDb]，读 assets/stock.db），与网络无关；
 * 这个开关现在只控制 **AI 是否走真实 DeepSeek**：
 * - ONLINE（默认）：AI 问答/分析直连 DeepSeek（真实模型，需联网 + 已配置 API Key）
 * - OFFLINE：AI 关闭，问答/分析回退本地模板回答（行情数据仍可正常浏览）
 *
 * 模式用普通 var 存放（不做跨页响应式绑定），各页在加载数据时读取；
 * 持久化由首页 ChatMainPage 通过 SharedPreferencesModule 读写。
 */
object DataSourceManager {

    enum class Mode { OFFLINE, ONLINE }

    /** 当前模式，默认在线（优先走真实后端 AI，不可达时由 Repository 自动回退离线） */
    var mode: Mode = Mode.ONLINE
        private set

    val isOnline: Boolean
        get() = mode == Mode.ONLINE

    fun setMode(m: Mode) {
        mode = m
    }

    /** SharedPreferences 持久化 key（与 ChatMainPage 共用） */
    const val PREFS_KEY = "data_source_mode"
}
