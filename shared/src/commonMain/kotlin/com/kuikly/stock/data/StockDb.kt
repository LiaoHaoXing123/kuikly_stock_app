package com.kuikly.stock.data

import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockListItem

/**
 * 方案 B：本机 SQLite 数据层（读 APK 内置的 assets/stock.db）
 *
 * - 数据全部离线，无需后端/数据线
 * - 与 [StockRepository] 配合：行情/K线/指标/分时/盘口 全部走这里
 * - Android 端用 android.database.sqlite 实现（见 androidMain actual）
 */
expect object StockDb {

    /** 列表（分页 + 关键词 + 排序） */
    fun listStocks(
        keyword: String?,
        sort: String?,
        order: String?,
        page: Int,
        size: Int,
    ): StockListPage

    /** 个股详情（基础信息 + 实时行情 + 最近30日K线 + 最新技术指标） */
    fun stockDetail(code: String): StockDetailData?

    /** 技术指标（最近 N 日，升序） */
    fun indicators(code: String, limit: Int): List<IndicatorData>

    /** 分时1分钟（自动取最新交易日） */
    fun minute(code: String): List<MinutePoint>

    /** 五档盘口（仅 11 只热门股有数据，无则 null） */
    fun orderBook(code: String): OrderBookData?

    /** 市场概览（涨跌家数 + 涨/跌幅榜前 3） */
    fun marketOverview(): MarketOverview

    /** 从用户消息识别提及的股票（6 位代码 + 名称，最多 3 只） */
    fun detectMentioned(message: String): List<StockListItem>

    /** 数据库是否就绪（assets 内 stock.db 已拷贝并可打开） */
    fun isAvailable(): Boolean

    /**
     * 用新下载的 SQLite（sourcePath）原子替换本地库并重新打开。
     * DataUpdateWorker 在后台把 GitHub Release 拉下来的 stock.db.tmp 交给此方法热切换。
     * 返回是否成功。
     */
    fun refreshFromFile(sourcePath: String): Boolean

    /** 数据来源标注（data_source 表：tableName -> source，如 stock_realtime -> 东方财富） */
    fun dataSources(): List<Pair<String, String>>
}

/** 列表分页结果 */
data class StockListPage(
    val total: Int,
    val items: List<StockListItem>,
)

/** 市场概览 */
data class MarketOverview(
    val total: Int,
    val up: Int,
    val down: Int,
    val flat: Int,
    val topGainers: List<StockListItem>,
    val topLosers: List<StockListItem>,
)
