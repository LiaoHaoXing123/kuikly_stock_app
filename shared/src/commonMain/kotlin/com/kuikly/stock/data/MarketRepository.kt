package com.kuikly.stock.data

import com.kuikly.stock.pages.FundFlowItem
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.KLineDataItem
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.RealtimeQuoteData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface LiveProvider {
    suspend fun realtimeQuote(code: String): RealtimeQuoteData?
}

data class QuoteSnapshot(
    val code: String,
    val name: String?,
    val quote: RealtimeQuoteData?,
    val asOf: String,
    val source: String,
)

data class EventsSnapshot(
    val code: String,
    val asOf: String,
    val covered: Boolean,
    val upcoming: List<CalendarEventMark>,
    val recent: List<CalendarEventMark>,
)

object MarketRepository {

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
