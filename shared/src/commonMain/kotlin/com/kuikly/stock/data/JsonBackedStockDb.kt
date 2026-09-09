// 无 SQLite 平台（iOS / JS）的行情库实现。
//
// 这些平台随包内置 stock_list.json / stock_kline.json（iOS 经 CocoaPods resources 打包，
// JS 经同步 XHR 加载），本实现直接复用 LocalDataService 的解析能力，语义与 Android 的
// SQLite 实现保持一致（排序键、分页规则、涨跌统计口径、mention 上限均为 3）。
// 明确不支持的能力：指数快照（包内无指数数据）、分时 / 盘口（无分钟级数据）、
// refreshFromFile（无 SQLite 可导入），调用方均有空态兜底。

package com.kuikly.stock.data

import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockListItem

internal object JsonBackedStockDb {

    fun listStocks(
        keyword: String?,
        sort: String?,
        order: String?,
        page: Int,
        size: Int,
    ): StockListPage {
        val all = if (keyword.isNullOrBlank()) {
            LocalDataService.loadStockList()
        } else {
            LocalDataService.searchStocks(keyword)
        }
        val asc = order.equals("asc", ignoreCase = true)
        val sorted = when (sort) {
            null -> all.sortedBy { it.code }
            "name" -> sortNullable(all, asc) { it.name }
            "change_percent" -> sortNullable(all, asc) { it.changePercent }
            "volume" -> sortNullable(all, asc) { it.volume }
            // StockListItem 无市值字段，退化为按代码排序
            else -> if (asc) all.sortedBy { it.code } else all.sortedByDescending { it.code }
        }
        val from = ((page - 1).coerceAtLeast(0)) * size.coerceAtLeast(1)
        return StockListPage(total = sorted.size, items = sorted.drop(from).take(size.coerceAtLeast(1)))
    }

    private fun <T : Comparable<T>> sortNullable(
        list: List<StockListItem>,
        asc: Boolean,
        key: (StockListItem) -> T?,
    ): List<StockListItem> {
        val (nulls, nonNulls) = list.partition { key(it) == null }
        val sorted = if (asc) nonNulls.sortedBy(key) else nonNulls.sortedByDescending(key)
        return sorted + nulls
    }

    fun stockDetail(code: String): StockDetailData? =
        LocalDataService.loadStockDetail(code)

    // 暂无调用方：技术指标一览尚未接入 UI，返回空列表。
    fun indicators(code: String, limit: Int): List<IndicatorData> = emptyList()

    // 包内无分钟级数据，调用方（分时卡片）有空态兜底。
    fun minute(code: String): List<MinutePoint> = emptyList()

    // 包内无盘口数据，调用方（盘口卡片）判空隐藏。
    fun orderBook(code: String): OrderBookData? = null

    fun marketOverview(): MarketOverview {
        val withPct = LocalDataService.loadStockList().filter { it.changePercent != null }
        return MarketOverview(
            total = withPct.size,
            up = withPct.count { (it.changePercent ?: 0.0) > 0 },
            down = withPct.count { (it.changePercent ?: 0.0) < 0 },
            flat = withPct.count { (it.changePercent ?: 0.0) == 0.0 },
            topGainers = withPct.sortedByDescending { it.changePercent }.take(3),
            topLosers = withPct.sortedBy { it.changePercent }.take(3),
        )
    }

    fun detectMentioned(message: String): List<StockListItem> {
        val all = LocalDataService.loadStockList()
        val found = mutableListOf<StockListItem>()
        Regex("(?<!\\d)\\d{6}(?!\\d)").findAll(message).map { it.value }.toSet().forEach { code ->
            all.firstOrNull { it.code == code }?.let {
                found.add(StockListItem(it.code, it.name, null, null))
            }
        }
        if (found.size < 3) {
            for (item in all) {
                if (found.size >= 3) break
                val name = item.name ?: continue
                if (name.length >= 2 && name in message && found.none { it.code == item.code }) {
                    found.add(StockListItem(item.code, item.name, null, null))
                }
            }
        }
        return found.take(3)
    }

    // 包内无指数快照：指数详情 / 指数列表在 iOS、JS 上返回空，调用方有「暂无数据」兜底。
    fun indexDetail(code: String): StockDetailData? = null

    fun detectMentionedIndices(message: String): List<StockListItem> = emptyList()

    fun listIndices(keyword: String?): List<StockListItem> = emptyList()

    fun isAvailable(): Boolean = LocalDataService.loadStockList().isNotEmpty()

    // 无 SQLite 可导入，始终返回 false（调用方视为「数据已是最新」）。
    fun refreshFromFile(sourcePath: String): Boolean = false

    fun dataSources(): List<Pair<String, String>> = listOf(
        "stock_list.json" to "内置 A 股快照",
        "stock_kline.json" to "内置日 K 线",
    )
}
