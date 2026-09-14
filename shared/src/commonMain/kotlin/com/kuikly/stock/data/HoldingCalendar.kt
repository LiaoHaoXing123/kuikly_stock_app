// 按持仓区间（起始日到结束日）计算每日市值与盈亏。

package com.kuikly.stock.data

import com.kuikly.stock.pages.StockDetailData
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.abs

internal const val CAL_EVENT_LIMIT_UP = "limit_up"
internal const val CAL_EVENT_LIMIT_DOWN = "limit_down"
internal const val CAL_EVENT_ALERT = "alert"
internal const val CAL_EVENT_DIVIDEND = "dividend"
internal const val CAL_EVENT_EARNINGS = "earnings"

internal data class HoldingDayLine(
    val code: String,
    val name: String,
    val shares: Double,
    val cost: Double,
    val price: Double,
    val changePercent: Double,
    val dayPnl: Double,
    val marketValue: Double,
    val limit: String = "",
)

data class CalendarEventMark(
    val kind: String,
    val code: String,
    val label: String,
    val date: String = "",
)

internal data class CalendarDaySnapshot(
    val date: String,
    val marketValue: Double,
    val dayPnl: Double,
    val dayPnlPct: Double,
    val hs300Pct: Double? = null,
    val holdings: List<HoldingDayLine> = emptyList(),
    val events: List<CalendarEventMark> = emptyList(),
) {
    val beatHs300: Boolean? get() {
        val bench = hs300Pct ?: return null
        return when {
            dayPnlPct > bench + 1e-9 -> true
            dayPnlPct < bench - 1e-9 -> false
            else -> null
        }
    }
}

internal data class CalendarStats(
    val monthPnl: Double,
    val monthDays: Int,
    val winDays: Int,
    val lossDays: Int,
    val winRate: Double,
    val streak: Int,
    val maxWin: Double,
    val maxLoss: Double,
    val beatDays: Int,
    val loseDays: Int,
)

internal data class QuoteProbe(
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val stockName: String = name,
)

internal fun limitThresholdPercent(code: String, name: String): Double {
    val n = name.uppercase()
    if (n.contains("ST")) return 5.0
    return when {
        code.startsWith("300") || code.startsWith("301") -> 20.0
        code.startsWith("688") || code.startsWith("689") -> 20.0
        code.startsWith("8") || code.startsWith("4") -> 30.0
        else -> 10.0
    }
}

internal fun classifyLimit(changePercent: Double, cap: Double): String {
    if (!changePercent.isFinite() || cap <= 0.0) return ""
    val edge = cap - 0.01
    return when {
        changePercent >= edge -> "up"
        changePercent <= -edge -> "down"
        else -> ""
    }
}

internal fun heatColor(pnl: Double, maxAbs: Double, emptyColor: Long): Long {
    if (maxAbs <= 0.0 || pnl == 0.0 || !pnl.isFinite()) return emptyColor
    val t = (abs(pnl) / maxAbs).coerceIn(0.0, 1.0)
    val alpha = when {
        t < 0.25 -> 0x48
        t < 0.50 -> 0x78
        t < 0.75 -> 0xB0
        else -> 0xE6
    }
    return if (pnl > 0) StockColors.up(alpha) else StockColors.down(alpha)
}

internal fun compactPnl(v: Double): String {
    if (!v.isFinite() || v == 0.0) return "0"
    val sign = if (v > 0) "+" else "-"
    val a = abs(v)
    return if (a >= 10000) sign + fmt1(a / 10000.0) + "万" else sign + fmt0(a)
}

internal fun arrowPnl(v: Double): String {
    if (!v.isFinite() || v == 0.0) return "0"
    val arrow = if (v > 0) "↑" else "↓"
    val a = abs(v)
    return if (a >= 10000) arrow + fmt1(a / 10000.0) + "万" else arrow + fmt0(a)
}

internal fun buildHoldingDaySnapshot(
    date: String,
    holdings: List<WatchHolding>,
    quoteOf: (String) -> QuoteProbe?,
    hs300Pct: Double?,
    alertHits: List<Pair<PriceAlertRule, String>>,
    extraEvents: List<CalendarEventMark> = emptyList(),
): CalendarDaySnapshot? {
    val positions = holdings.filter { it.hasCalendarPosition() && it.activeOn(date) }
    if (positions.isEmpty()) return null
    val lines = mutableListOf<HoldingDayLine>()
    val events = extraEvents.toMutableList()
    var marketValue = 0.0
    var dayPnl = 0.0
    for (h in positions) {
        val q = quoteOf(h.code) ?: continue
        if (!q.price.isFinite() || q.price <= 0.0) continue
        val change = if (q.change.isFinite()) q.change else q.price * q.changePercent / 100.0
        val linePnl = change * h.shares
        val mv = q.price * h.shares
        if (!linePnl.isFinite() || !mv.isFinite()) continue
        val name = h.name.ifBlank { q.stockName }.ifBlank { h.code }
        val cap = limitThresholdPercent(h.code, name)
        val limit = classifyLimit(q.changePercent, cap)
        lines.add(
            HoldingDayLine(
                code = h.code,
                name = name,
                shares = h.shares,
                cost = h.cost,
                price = q.price,
                changePercent = q.changePercent,
                dayPnl = linePnl,
                marketValue = mv,
                limit = limit,
            )
        )
        marketValue += mv
        dayPnl += linePnl
        if (limit == "up") events.add(CalendarEventMark(CAL_EVENT_LIMIT_UP, h.code, "${name} 涨停"))
        if (limit == "down") events.add(CalendarEventMark(CAL_EVENT_LIMIT_DOWN, h.code, "${name} 跌停"))
    }
    if (lines.size != positions.size) return null
    val prevMv = marketValue - dayPnl
    val dayPnlPct = if (prevMv > 0.0) dayPnl / prevMv * 100.0 else 0.0
    val holdingCodes = lines.map { it.code }.toSet()
    for ((rule, message) in alertHits) {
        if (rule.code in holdingCodes) {
            events.add(CalendarEventMark(CAL_EVENT_ALERT, rule.code, message))
        }
    }
    return CalendarDaySnapshot(
        date = date,
        marketValue = marketValue,
        dayPnl = dayPnl,
        dayPnlPct = dayPnlPct,
        hs300Pct = hs300Pct,
        holdings = lines.sortedByDescending { abs(it.dayPnl) },
        events = events,
    )
}

internal fun calendarStatsOf(days: List<CalendarDaySnapshot>, monthPrefix: String): CalendarStats {
    val month = days.filter { it.date.startsWith(monthPrefix) }
    val monthPnl = month.sumOf { it.dayPnl }
    val winDays = month.count { it.dayPnl > 0 }
    val lossDays = month.count { it.dayPnl < 0 }
    val decided = winDays + lossDays
    val winRate = if (decided > 0) winDays.toDouble() / decided * 100.0 else 0.0
    val chronological = days.sortedBy { it.date }
    var streak = 0
    if (chronological.isNotEmpty()) {
        val last = chronological.last()
        val sign = when {
            last.dayPnl > 0 -> 1
            last.dayPnl < 0 -> -1
            else -> 0
        }
        if (sign != 0) {
            streak = sign
            for (i in chronological.lastIndex - 1 downTo 0) {
                val p = chronological[i].dayPnl
                val s = when {
                    p > 0 -> 1
                    p < 0 -> -1
                    else -> 0
                }
                if (s != sign) break
                streak += sign
            }
        }
    }
    val maxWin = days.maxOfOrNull { it.dayPnl }?.coerceAtLeast(0.0) ?: 0.0
    val maxLoss = days.minOfOrNull { it.dayPnl }?.coerceAtMost(0.0) ?: 0.0
    val beatDays = month.count { it.beatHs300 == true }
    val loseDays = month.count { it.beatHs300 == false }
    return CalendarStats(
        monthPnl = monthPnl,
        monthDays = month.size,
        winDays = winDays,
        lossDays = lossDays,
        winRate = winRate,
        streak = streak,
        maxWin = maxWin,
        maxLoss = maxLoss,
        beatDays = beatDays,
        loseDays = loseDays,
    )
}

internal class HoldingCalendarRepository(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {

    private val key = "holding_calendar_dated_v2"
    private val cap = 800

    fun days(): List<CalendarDaySnapshot> = decodeDays(read(key)).sortedBy { it.date }

    fun find(date: String): CalendarDaySnapshot? = days().firstOrNull { it.date == date }

    fun replace(days: List<CalendarDaySnapshot>) {
        write(key, encodeDays(days.sortedBy { it.date }.takeLast(cap)))
    }

    fun upsert(day: CalendarDaySnapshot) {
        val next = days().filterNot { it.date == day.date } + day
        val trimmed = next.sortedBy { it.date }.takeLast(cap)
        write(key, encodeDays(trimmed))
    }
}

internal object HoldingCalendar {
    private val store = HoldingCalendarRepository(::appPrefsGet, ::appPrefsSet)

    fun days(): List<CalendarDaySnapshot> = store.days()

    fun find(date: String): CalendarDaySnapshot? = store.find(date)

    fun syncQuietly(): CalendarDaySnapshot? = runCatching { sync() }.getOrNull()

    fun sync(): CalendarDaySnapshot? {
        backfill()
        return store.days().lastOrNull()
    }

    private fun probeQuote(code: String): QuoteProbe? {
        val d = runCatching { StockDb.stockDetail(code) }.getOrNull() ?: return null
        return quoteFromDetail(d)
    }

    fun backfill(): Int {
        val holdings = WatchStore.list().filter { it.shares > 0 && it.shares.isFinite() }
        val histories = holdings.associate { h -> h.code to
            runCatching { StockDb.stockDetail(h.code)?.kline.orEmpty() }.getOrDefault(emptyList()) }
        val events = holdings.flatMap { h ->
            runCatching { StockDb.dividendEvents(h.code) }.getOrDefault(emptyList()) +
                runCatching { StockDb.earningsEvents(h.code) }.getOrDefault(emptyList())
        }
        val next = datedPortfolioDays(holdings, histories, hs300DailyPct(), events)
        store.replace(next)
        return next.size
    }

    fun backfillQuietly(): Int = runCatching { backfill() }.getOrDefault(0)

    private fun hs300DailyPct(): Map<String, Double> {
        for (code in listOf("000300", "399300")) {
            val bars = runCatching { StockDb.indexDetail(code)?.kline }.getOrNull().orEmpty()
                .sortedBy { it.tradeDate }
            if (bars.size >= 2) {
                val out = HashMap<String, Double>()
                for (i in 1 until bars.size) {
                    val prev = bars[i - 1].close
                    if (prev.isFinite() && prev > 0.0 && bars[i].close.isFinite()) {
                        out[bars[i].tradeDate] = (bars[i].close - prev) / prev * 100.0
                    }
                }
                if (out.isNotEmpty()) return out
            }
        }
        return emptyMap()
    }

    private fun probeHs300(): Double? {
        for (code in listOf("000300", "399300")) {
            val pct = runCatching { StockDb.indexDetail(code)?.realtime?.changePercent }.getOrNull()
            if (pct != null && pct.isFinite()) return pct
        }
        return null
    }
}

internal fun quoteFromDetail(d: StockDetailData): QuoteProbe? {
    val rt = d.realtime ?: return null
    val price = rt.price ?: return null
    if (!price.isFinite() || price <= 0.0) return null
    val pct = rt.changePercent ?: 0.0
    val change = rt.change ?: (price * pct / 100.0)
    val name = rt.name ?: d.info?.name ?: ""
    return QuoteProbe(
        name = name,
        price = price,
        change = change,
        changePercent = pct,
        stockName = name,
    )
}

internal data class CalendarCellVm(
    val date: String,
    val dayNum: Int,
    val inMonth: Boolean,
    val pnl: Double?,
    val pnlText: String,
    val color: Long,
    val vsHs300: String,
    val events: List<CalendarEventMark>,
    val snapshot: CalendarDaySnapshot?,
)

internal fun monthCells(
    year: Int,
    month: Int,
    byDate: Map<String, CalendarDaySnapshot>,
    maxAbs: Double,
    extraEventsByDate: Map<String, List<CalendarEventMark>> = emptyMap(),

    emptyColor: Long = 0xFFF7F8FA,

    outMonthColor: Long = 0xFFF2F4F7,
): List<List<CalendarCellVm>> {
    val first = CivilDate(year, month, 1)
    val start = first.mondayOfWeek()
    val weeks = mutableListOf<List<CalendarCellVm>>()
    var cursor = start
    repeat(6) {
        val row = (0 until 7).map {
            val d = cursor.plusDays(it)
            val snap = byDate[d.iso]
            val vs = when (snap?.beatHs300) {
                true -> "赢"
                false -> "输"
                null -> ""
            }
            CalendarCellVm(
                date = d.iso,
                dayNum = d.day,
                inMonth = d.month == month,
                pnl = snap?.dayPnl,
                pnlText = if (snap != null) arrowPnl(snap.dayPnl) else "",
                color = if (snap != null) heatColor(snap.dayPnl, maxAbs, emptyColor) else if (d.month == month) emptyColor else outMonthColor,
                vsHs300 = vs,
                events = (snap?.events.orEmpty() + extraEventsByDate[d.iso].orEmpty()).distinctBy { it.kind + it.code + it.label },
                snapshot = snap,
            )
        }
        weeks.add(row)
        cursor = cursor.plusDays(7)
    }
    return weeks
}

internal data class HeatCellVm(val date: String, val color: Long, val hasData: Boolean)

internal fun heatStrip(days: List<CalendarDaySnapshot>, maxAbs: Double, emptyColor: Long = 0xFFEEF1F4): List<HeatCellVm> {
    if (days.isEmpty()) return emptyList()
    val first = CivilDate.parse(days.first().date) ?: return emptyList()
    val last = CivilDate.parse(days.last().date) ?: return emptyList()
    val start = first.mondayOfWeek()
    val end = last.sundayOfWeek()
    val byDate = days.associateBy { it.date }
    val out = mutableListOf<HeatCellVm>()
    var d = start
    while (d <= end) {
        val snap = byDate[d.iso]
        out.add(
            HeatCellVm(
                date = d.iso,
                color = if (snap != null) heatColor(snap.dayPnl, maxAbs, emptyColor) else emptyColor,
                hasData = snap != null,
            )
        )
        d = d.plusDays(1)
    }
    return out
}

internal fun encodeDays(days: List<CalendarDaySnapshot>): String {
    val arr = JSONArray()
    for (d in days) arr.put(encodeDay(d))
    return JSONObject().put("days", arr).toString()
}

internal fun decodeDays(raw: String?): List<CalendarDaySnapshot> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("days") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                decodeDay(o)?.let { add(it) }
            }
        }
    } catch (e: Throwable) {
        emptyList()
    }
}

private fun encodeDay(d: CalendarDaySnapshot): JSONObject {
    val holdings = JSONArray()
    for (h in d.holdings) {
        holdings.put(
            JSONObject()
                .put("code", h.code)
                .put("name", h.name)
                .put("shares", h.shares)
                .put("cost", h.cost)
                .put("price", h.price)
                .put("changePercent", h.changePercent)
                .put("dayPnl", h.dayPnl)
                .put("marketValue", h.marketValue)
                .put("limit", h.limit)
        )
    }
    val events = JSONArray()
    for (e in d.events) {
        events.put(
            JSONObject()
                .put("kind", e.kind)
                .put("code", e.code)
                .put("label", e.label)
                .put("date", e.date)
        )
    }
    val o = JSONObject()
        .put("date", d.date)
        .put("marketValue", d.marketValue)
        .put("dayPnl", d.dayPnl)
        .put("dayPnlPct", d.dayPnlPct)
        .put("holdings", holdings)
        .put("events", events)
    d.hs300Pct?.let { o.put("hs300Pct", it.toString()) }
    return o
}

private fun decodeDay(o: JSONObject): CalendarDaySnapshot? {
    val date = o.optString("date", "")
    if (CivilDate.parse(date) == null) return null
    val holdingsArr = o.optJSONArray("holdings")
    val holdings = buildList {
        if (holdingsArr != null) {
            for (i in 0 until holdingsArr.length()) {
                val h = holdingsArr.optJSONObject(i) ?: continue
                add(
                    HoldingDayLine(
                        code = h.optString("code", ""),
                        name = h.optString("name", ""),
                        shares = h.optDouble("shares", 0.0),
                        cost = h.optDouble("cost", 0.0),
                        price = h.optDouble("price", 0.0),
                        changePercent = h.optDouble("changePercent", 0.0),
                        dayPnl = h.optDouble("dayPnl", 0.0),
                        marketValue = h.optDouble("marketValue", 0.0),
                        limit = h.optString("limit", ""),
                    )
                )
            }
        }
    }
    val eventsArr = o.optJSONArray("events")
    val events = buildList {
        if (eventsArr != null) {
            for (i in 0 until eventsArr.length()) {
                val e = eventsArr.optJSONObject(i) ?: continue
                add(
                    CalendarEventMark(
                        kind = e.optString("kind", ""),
                        code = e.optString("code", ""),
                        label = e.optString("label", ""),
                        date = e.optString("date", ""),
                    )
                )
            }
        }
    }
    val hsRaw = o.optString("hs300Pct", "")
    val hs300 = hsRaw.toDoubleOrNull()
    return CalendarDaySnapshot(
        date = date,
        marketValue = o.optDouble("marketValue", 0.0),
        dayPnl = o.optDouble("dayPnl", 0.0),
        dayPnlPct = o.optDouble("dayPnlPct", 0.0),
        hs300Pct = hs300,
        holdings = holdings,
        events = events,
    )
}
