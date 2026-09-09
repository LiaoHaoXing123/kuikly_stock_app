// 数据库查询接口声明与结果类型，各平台提供实现。

package com.kuikly.stock.data

import com.kuikly.stock.pages.FundFlowItem
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockListItem

expect object StockDb {

    fun listStocks(
        keyword: String?,
        sort: String?,
        order: String?,
        page: Int,
        size: Int,
    ): StockListPage

    fun stockDetail(code: String): StockDetailData?

    fun indicators(code: String, limit: Int): List<IndicatorData>

    fun minute(code: String): List<MinutePoint>

    fun orderBook(code: String): OrderBookData?

    fun fundFlow(code: String, limit: Int): List<FundFlowItem>

    fun marketOverview(): MarketOverview

    fun detectMentioned(message: String): List<StockListItem>

    fun indexDetail(code: String): StockDetailData?

    fun detectMentionedIndices(message: String): List<StockListItem>

    fun listIndices(keyword: String?): List<StockListItem>

    fun isAvailable(): Boolean

    fun refreshFromFile(sourcePath: String): Boolean

    fun dataSources(): List<Pair<String, String>>
}

data class StockListPage(
    val total: Int,
    val items: List<StockListItem>,
)

data class MarketOverview(
    val total: Int,
    val up: Int,
    val down: Int,
    val flat: Int,
    val topGainers: List<StockListItem>,
    val topLosers: List<StockListItem>,
)
