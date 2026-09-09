// iOS 行情库：无 SQLite，委托给包内置 JSON 实现（见 JsonBackedStockDb）。

package com.kuikly.stock.data

import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockListItem

actual object StockDb {

    actual fun listStocks(
        keyword: String?,
        sort: String?,
        order: String?,
        page: Int,
        size: Int,
    ): StockListPage =
        JsonBackedStockDb.listStocks(keyword, sort, order, page, size)

    actual fun stockDetail(code: String): StockDetailData? =
        JsonBackedStockDb.stockDetail(code)

    actual fun indicators(code: String, limit: Int): List<IndicatorData> =
        JsonBackedStockDb.indicators(code, limit)

    actual fun minute(code: String): List<MinutePoint> =
        JsonBackedStockDb.minute(code)

    actual fun orderBook(code: String): OrderBookData? =
        JsonBackedStockDb.orderBook(code)

    actual fun marketOverview(): MarketOverview =
        JsonBackedStockDb.marketOverview()

    actual fun detectMentioned(message: String): List<StockListItem> =
        JsonBackedStockDb.detectMentioned(message)

    actual fun indexDetail(code: String): StockDetailData? =
        JsonBackedStockDb.indexDetail(code)

    actual fun detectMentionedIndices(message: String): List<StockListItem> =
        JsonBackedStockDb.detectMentionedIndices(message)

    actual fun listIndices(keyword: String?): List<StockListItem> =
        JsonBackedStockDb.listIndices(keyword)

    actual fun isAvailable(): Boolean =
        JsonBackedStockDb.isAvailable()

    actual fun refreshFromFile(sourcePath: String): Boolean =
        JsonBackedStockDb.refreshFromFile(sourcePath)

    actual fun dataSources(): List<Pair<String, String>> =
        JsonBackedStockDb.dataSources()
}
