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

/**
 * 涨跌停阈值：按代码段 + 名称里的 ST。
 * 0.01 的容差是因为行情里 9.99% 经常就是 10% 涨停（四舍五入）。
 */
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

internal fun heatColor(pnl: Double, maxAbs: Double): Long {
    if (maxAbs <= 0.0 || pnl == 0.0 || !pnl.isFinite()) return 0xFFE8ECF1
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
    val positions = holdings.filter { it.shares > 0.0 && it.shares.isFinite() }
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
    if (lines.isEmpty()) return null
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
    private val key = "holding_calendar_v1"
    private val cap = 800

    fun days(): List<CalendarDaySnapshot> = decodeDays(read(key)).sortedBy { it.date }

    fun find(date: String): CalendarDaySnapshot? = days().firstOrNull { it.date == date }

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

    /** 任何入口都可以调：没有持仓、没有交易日、解析失败都吞掉。 */
    fun syncQuietly(): CalendarDaySnapshot? = runCatching { sync() }.getOrNull()

    /**
     * 用「当前行情快照的交易日」记一笔持仓（真实日记：随当天持仓变化如实记录）。
     * 历史交易日由 [backfill] 用日线收盘价补齐；同一天再更新会覆盖。
     */
    fun sync(): CalendarDaySnapshot? {
        val date = runCatching { StockDb.latestTradeDate() }.getOrDefault("").trim()
        if (date.isEmpty() || CivilDate.parse(date) == null) return null
        val holdings = WatchStore.list()
        val extraEvents = buildList {
            for (h in holdings) {
                if (h.shares <= 0.0 || !h.shares.isFinite()) continue
                addAll(runCatching { StockDb.dividendEvents(h.code) }.getOrDefault(emptyList()).filter { it.date == date })
                addAll(runCatching { StockDb.earningsEvents(h.code) }.getOrDefault(emptyList()).filter { it.date == date })
            }
        }
        val snap = buildHoldingDaySnapshot(
            date = date,
            holdings = holdings,
            quoteOf = { code -> probeQuote(code) },
            hs300Pct = probeHs300(),
            alertHits = runCatching { AlertEngine.hits() }.getOrDefault(emptyList()),
            extraEvents = extraEvents,
        ) ?: return store.find(date)
        store.upsert(snap)
        return snap
    }

    private fun probeQuote(code: String): QuoteProbe? {
        val d = runCatching { StockDb.stockDetail(code) }.getOrNull() ?: return null
        return quoteFromDetail(d)
    }

    /**
     * 用「当前持仓 + 各股日线收盘价历史」把最近的交易日补齐。
     *
     * 某天盈亏 = Σ (当日收盘 − 前一交易日收盘) × 当前股数；市值 = Σ 当日收盘 × 当前股数。
     * 语义是「按我现在的持仓，过去这些天每天赚 / 亏多少」——正是用户要的
     * 「结合自持股算上一天亏了多少」。数据来源就是个股详情页那张日线表（约 30 个交易日）。
     *
     * 只补 store 里**还没有**的日期：sync() 逐日记下的真实快照是随持仓变化的日记，
     * 比这里的「等仓估算」准，不能被覆盖。因此本方法反复调用是幂等的（已补过的天不再动）。
     *
     * @return 本次新补入的交易日数量。
     */
    fun backfill(): Int {
        val positions = WatchStore.list().filter { it.shares > 0.0 && it.shares.isFinite() }
        if (positions.isEmpty()) return 0
        val existing = store.days().map { it.date }.toSet()
        val hs300 = hs300DailyPct()

        val mvByDate = HashMap<String, Double>()
        val pnlByDate = HashMap<String, Double>()
        val linesByDate = HashMap<String, MutableList<HoldingDayLine>>()
        val eventsByDate = HashMap<String, MutableList<CalendarEventMark>>()
        // 除权/财报事件：从 StockDb 读取，按日期预填入 eventsByDate
        for (h in positions) {
            runCatching { StockDb.dividendEvents(h.code) }.getOrDefault(emptyList()).forEach { e ->
                if (e.date.isNotEmpty()) eventsByDate.getOrPut(e.date) { mutableListOf() }.add(e)
            }
            runCatching { StockDb.earningsEvents(h.code) }.getOrDefault(emptyList()).forEach { e ->
                if (e.date.isNotEmpty()) eventsByDate.getOrPut(e.date) { mutableListOf() }.add(e)
            }
        }

        for (h in positions) {
            val detail = runCatching { StockDb.stockDetail(h.code) }.getOrNull() ?: continue
            // 收盘价历史按日期升序（YYYY-MM-DD 字典序即时间序），保证 bars[i-1] 是前一交易日。
            val bars = detail.kline.orEmpty().sortedBy { it.tradeDate }
            if (bars.size < 2) continue
            val name = h.name.ifBlank { detail.realtime?.name ?: detail.info?.name ?: h.code }
            val cap = limitThresholdPercent(h.code, name)
            for (i in 1 until bars.size) {
                val cur = bars[i]
                val date = cur.tradeDate
                if (date in existing) continue
                if (CivilDate.parse(date) == null) continue
                val prevClose = bars[i - 1].close
                if (!prevClose.isFinite() || prevClose <= 0.0) continue
                if (!cur.close.isFinite() || cur.close <= 0.0) continue
                val change = cur.close - prevClose
                val pct = change / prevClose * 100.0
                val linePnl = change * h.shares
                val mv = cur.close * h.shares
                if (!linePnl.isFinite() || !mv.isFinite()) continue
                val limit = classifyLimit(pct, cap)
                mvByDate[date] = (mvByDate[date] ?: 0.0) + mv
                pnlByDate[date] = (pnlByDate[date] ?: 0.0) + linePnl
                linesByDate.getOrPut(date) { mutableListOf() }.add(
                    HoldingDayLine(
                        code = h.code,
                        name = name,
                        shares = h.shares,
                        cost = h.cost,
                        price = cur.close,
                        changePercent = pct,
                        dayPnl = linePnl,
                        marketValue = mv,
                        limit = limit,
                    )
                )
                if (limit == "up") eventsByDate.getOrPut(date) { mutableListOf() }
                    .add(CalendarEventMark(CAL_EVENT_LIMIT_UP, h.code, "$name 涨停"))
                if (limit == "down") eventsByDate.getOrPut(date) { mutableListOf() }
                    .add(CalendarEventMark(CAL_EVENT_LIMIT_DOWN, h.code, "$name 跌停"))
            }
        }

        var written = 0
        for ((date, dayLines) in linesByDate) {
            if (dayLines.isEmpty()) continue
            val mv = mvByDate[date] ?: continue
            val dayPnl = pnlByDate[date] ?: 0.0
            val prevMv = mv - dayPnl
            val dayPnlPct = if (prevMv > 0.0) dayPnl / prevMv * 100.0 else 0.0
            store.upsert(
                CalendarDaySnapshot(
                    date = date,
                    marketValue = mv,
                    dayPnl = dayPnl,
                    dayPnlPct = dayPnlPct,
                    hs300Pct = hs300[date],
                    holdings = dayLines.sortedByDescending { abs(it.dayPnl) },
                    events = eventsByDate[date].orEmpty(),
                )
            )
            written++
        }
        return written
    }

    /** 补齐历史；没持仓、没日线、解析失败都吞掉，返回补入天数（失败为 0）。 */
    fun backfillQuietly(): Int = runCatching { backfill() }.getOrDefault(0)

    /** 沪深300 每个交易日的涨跌幅（%），按日期查表，供历史天的跑赢 / 跑输判定。 */
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
                color = if (snap != null) heatColor(snap.dayPnl, maxAbs) else if (d.month == month) 0xFFF7F8FA else 0xFFF2F4F7,
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

internal fun heatStrip(days: List<CalendarDaySnapshot>, maxAbs: Double): List<HeatCellVm> {
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
                color = if (snap != null) heatColor(snap.dayPnl, maxAbs) else 0xFFEEF1F4,
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
