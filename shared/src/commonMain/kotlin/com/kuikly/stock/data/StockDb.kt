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
    fun industryPeers(code: String): IndustrySnapshot?
    fun sectorOfStock(code: String): SectorSnapshot?

    fun marketOverview(): MarketOverview

    fun detectMentioned(message: String): List<StockListItem>

    fun indexDetail(code: String): StockDetailData?

    fun detectMentionedIndices(message: String): List<StockListItem>

    fun listIndices(keyword: String?): List<StockListItem>

    fun isAvailable(): Boolean

    fun refreshFromFile(sourcePath: String): Boolean

    fun dataSources(): List<Pair<String, String>>

    /**
     * 全库最新交易日（YYYY-MM-DD），取不到返回空串。
     *
     * 「数据到哪一天了」只允许有一个答案：口径就是个股详情页用的那张日线表
     * （Android：`stock_daily_kline`；JSON 资产：`stock_kline.json`），取全库最大值。
     * 首页、我的页都读它，避免出现「首页说 09-10、个股说 09-11」这种
     * 同一份数据两个日期的情况。
     *
     * 注：单只个股的日期仍以它自己的日线为准——库里各股更新进度可能不一致
     * （实测同一时刻 25 只到 09-11、其余停在 09-10），全库最大值代表「数据最新到哪天」。
     */
    fun latestTradeDate(): String
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
