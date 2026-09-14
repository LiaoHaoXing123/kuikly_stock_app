package com.kuikly.stock.data

import com.kuikly.stock.pages.KLineDataItem

internal fun WatchHolding.hasCalendarPosition(): Boolean {
    if (!shares.isFinite() || shares <= 0 || !cost.isFinite() || cost <= 0) return false
    val start = CivilDate.parse(startDate) ?: return false
    if (start.year !in 1900..9999) return false
    if (endDate.isBlank()) return true
    val end = CivilDate.parse(endDate) ?: return false
    return end.year in 1900..9999 && end >= start
}

internal fun datedPortfolioDays(
    holdings: List<WatchHolding>, histories: Map<String, List<KLineDataItem>>,
    benchmark: Map<String, Double> = emptyMap(), events: List<CalendarEventMark> = emptyList(),
): List<CalendarDaySnapshot> {
    val held = holdings.filter { it.shares > 0 && it.shares.isFinite() }

    if (held.isEmpty() || held.any { !it.hasCalendarPosition() }) return emptyList()
    val sorted = held.associate { h -> h.code to histories[h.code].orEmpty()
        .filter { CivilDate.parse(it.tradeDate) != null }.distinctBy { it.tradeDate }.sortedBy { it.tradeDate } }
    val quotes = held.associate { h ->
        val bars = sorted[h.code].orEmpty()
        h.code to bars.mapIndexedNotNull { i, bar ->
            if (!h.activeOn(bar.tradeDate) || !bar.close.isFinite() || bar.close <= 0) return@mapIndexedNotNull null
            val prev = bars.getOrNull(i - 1)
            val entry = bar.tradeDate == h.startDate || (prev != null && prev.tradeDate < h.startDate)
            // 建仓日以成本为基准，后续交易日使用前收盘价。
            val basis = if (entry) h.cost else prev?.close ?: return@mapIndexedNotNull null
            if (!basis.isFinite() || basis <= 0) return@mapIndexedNotNull null
            val marketPct = prev?.close?.takeIf { it.isFinite() && it > 0 }
                ?.let { (bar.close / it - 1) * 100 } ?: 0.0
            bar.tradeDate to QuoteProbe(h.name, bar.close, bar.close - basis, marketPct)
        }.toMap()
    }
    return quotes.values.flatMap { it.keys }.distinct().sorted().mapNotNull { date ->
        val active = held.filter { it.activeOn(date) }
        if (active.any { quotes[it.code]?.get(date) == null }) return@mapNotNull null
        buildHoldingDaySnapshot(date, active, { quotes[it]?.get(date) }, benchmark[date], emptyList(),
            events.filter { it.date == date && active.any { h -> h.code == it.code } })
    }
}
