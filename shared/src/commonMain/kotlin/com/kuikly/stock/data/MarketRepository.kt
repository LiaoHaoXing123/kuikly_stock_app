// 统一行情仓库：AI 工具、分析提示词与详情页共用同一取数入口。
// 每份快照携带数据时间与来源；缺失字段一律 null（调用方显示"未提供"），禁止用 0 谎报。
// 实时层接入点：实现 LiveProvider 并注册到 liveProvider，页面与 AI 自动同步受益；
// 未注册时全部回落本地库，不会把本地数据误标成实时。

package com.kuikly.stock.data

import com.kuikly.stock.pages.FundFlowItem
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.KLineDataItem
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.RealtimeQuoteData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 实时数据提供者。返回 null 表示该股当前无实时数据，调用方回落本地库。 */
interface LiveProvider {
    suspend fun realtimeQuote(code: String): RealtimeQuoteData?
}

/** 单只股票的行情快照：数据 + 出处。 */
data class QuoteSnapshot(
    val code: String,
    val name: String?,
    val quote: RealtimeQuoteData?,
    val asOf: String,
    val source: String,
)

/** 公司事件快照：按最新交易日区分未发生/已发生；covered=false 表示事件库未收录该股。 */
data class EventsSnapshot(
    val code: String,
    val asOf: String,
    val covered: Boolean,
    val upcoming: List<CalendarEventMark>,
    val recent: List<CalendarEventMark>,
)

object MarketRepository {

    /** 实时层接入点；未注册（默认）时全部回落本地库。 */
    var liveProvider: LiveProvider? = null

    suspend fun stockQuote(code: String): QuoteSnapshot = withContext(Dispatchers.Default) {
        val live = runCatching { liveProvider?.realtimeQuote(code) }.getOrNull()
        val detail = runCatching { StockDb.stockDetail(code) }.getOrNull()
        QuoteSnapshot(
            code = code,
            name = detail?.info?.name,
            quote = live ?: detail?.realtime,
            asOf = runCatching { StockDb.latestTradeDate() }.getOrDefault(""),
            source = if (live != null) "实时层" else "本地库",
        )
    }

    fun events(code: String): EventsSnapshot {
        val asOf = runCatching { StockDb.latestTradeDate() }.getOrDefault("")
        val dividends = runCatching { StockDb.dividendEvents(code) }.getOrDefault(emptyList())
        val earnings = runCatching { StockDb.earningsEvents(code) }.getOrDefault(emptyList())
        val all = (dividends + earnings).filter { it.date.length >= 8 }.sortedBy { it.date }
        return EventsSnapshot(
            code = code,
            asOf = asOf,
            covered = all.isNotEmpty(),
            upcoming = all.filter { it.date > asOf }.takeLast(4).reversed(),
            recent = all.filter { it.date <= asOf }.takeLast(4).reversed(),
        )
    }

    fun klineTail(code: String, days: Int): List<KLineDataItem> =
        runCatching { StockDb.stockDetail(code)?.kline.orEmpty().takeLast(days.coerceIn(1, 250)) }.getOrDefault(emptyList())

    fun indicator(code: String): IndicatorData? =
        runCatching { StockDb.stockDetail(code)?.indicator }.getOrNull()

    fun indicatorSeries(code: String, limit: Int): List<IndicatorData> =
        runCatching { StockDb.indicators(code, limit.coerceIn(1, 120)) }.getOrDefault(emptyList())

    fun orderBook(code: String): OrderBookData? =
        runCatching { StockDb.orderBook(code) }.getOrNull()

    fun fundFlow(code: String, days: Int): List<FundFlowItem> =
        runCatching { StockDb.fundFlow(code, days.coerceIn(1, 60)) }.getOrDefault(emptyList())

    fun minute(code: String): List<MinutePoint> =
        runCatching { StockDb.minute(code) }.getOrDefault(emptyList())

    fun industryPeers(code: String): IndustrySnapshot? =
        runCatching { StockDb.industryPeers(code) }.getOrNull()
}
