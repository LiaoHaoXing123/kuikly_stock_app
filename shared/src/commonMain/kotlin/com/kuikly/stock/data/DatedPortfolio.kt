package com.kuikly.stock.data

import com.kuikly.stock.pages.KLineDataItem

internal fun WatchHolding.hasCalendarPosition(): Boolean = shares.isFinite() && shares > 0 &&
    cost.isFinite() && cost > 0 && CivilDate.parse(startDate)?.year in 1900..9999

/** Recomputed estimate from the current position configuration, not a transaction ledger. */
internal fun datedPortfolioDays(
    holdings: List<WatchHolding>, histories: Map<String, List<KLineDataItem>>,
    benchmark: Map<String, Double> = emptyMap(), events: List<CalendarEventMark> = emptyList(),
): List<CalendarDaySnapshot> {
    val held = holdings.filter { it.shares > 0 && it.shares.isFinite() }
    // An undated holding must not silently disappear from a purported portfolio total.
    if (held.isEmpty() || held.any { !it.hasCalendarPosition() }) return emptyList()
    val sorted = held.associate { h -> h.code to histories[h.code].orEmpty()
        .filter { CivilDate.parse(it.tradeDate) != null }.distinctBy { it.tradeDate }.sortedBy { it.tradeDate } }
    val quotes = held.associate { h ->
        val bars = sorted[h.code].orEmpty()
        h.code to bars.mapIndexedNotNull { i, bar ->
            if (bar.tradeDate < h.startDate || !bar.close.isFinite() || bar.close <= 0) return@mapIndexedNotNull null
            val prev = bars.getOrNull(i - 1)
            val entry = bar.tradeDate == h.startDate || (prev != null && prev.tradeDate < h.startDate)
            val basis = if (entry) h.cost else prev?.close ?: return@mapIndexedNotNull null
            if (!basis.isFinite() || basis <= 0) return@mapIndexedNotNull null
            val marketPct = prev?.close?.takeIf { it.isFinite() && it > 0 }
                ?.let { (bar.close / it - 1) * 100 } ?: 0.0
            bar.tradeDate to QuoteProbe(h.name, bar.close, bar.close - basis, marketPct)
        }.toMap()
    }
    return quotes.values.flatMap { it.keys }.distinct().sorted().mapNotNull { date ->
        val active = held.filter { it.startDate <= date }
        if (active.any { quotes[it.code]?.get(date) == null }) return@mapNotNull null
        buildHoldingDaySnapshot(date, active, { quotes[it]?.get(date) }, benchmark[date], emptyList(),
            events.filter { it.date == date && active.any { h -> h.code == it.code } })
    }
}
