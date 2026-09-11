package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.base.Overlay
import com.kuikly.stock.base.PressState
import com.kuikly.stock.base.overlayEnterExit
import com.kuikly.stock.base.pressFeedback
import com.kuikly.stock.base.pressedScale
import com.kuikly.stock.data.CAL_EVENT_ALERT
import com.kuikly.stock.data.CAL_EVENT_DIVIDEND
import com.kuikly.stock.data.CAL_EVENT_EARNINGS
import com.kuikly.stock.data.CAL_EVENT_LIMIT_DOWN
import com.kuikly.stock.data.CAL_EVENT_LIMIT_UP
import com.kuikly.stock.data.CalendarCellVm
import com.kuikly.stock.data.CalendarDaySnapshot
import com.kuikly.stock.data.CivilDate
import com.kuikly.stock.data.HeatCellVm
import com.kuikly.stock.data.HoldingCalendar
import com.kuikly.stock.data.StockColors
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.calendarStatsOf
import com.kuikly.stock.data.compactPnl
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import com.kuikly.stock.data.heatStrip
import com.kuikly.stock.data.monthCells
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent

import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.abs

internal data class CalendarWeekRow(val key: String, val days: List<CalendarCellVm>)

@Page(AppRoutes.CALENDAR)
class HoldingCalendarPage : BasePager() {

    internal var loading by observable(false)
    internal var hasHoldings by observable(false)
    internal var monthTitle by observable("盈亏日历")
    internal var subtitle by observable("行情更新后自动记下当天持仓")
    internal var viewYear by observable(2026)
    internal var viewMonth by observable(9)
    internal var canPrev by observable(false)
    internal var canNext by observable(false)
    internal var monthPnlText by observable("--")
    internal var monthPnlColor by observable(StockColors.FLAT)
    internal var winRateText by observable("--")
    internal var streakText by observable("--")
    internal var extremaText by observable("--")
    internal var beatText by observable("对照沪深300，避免躺赢误判")
    internal var selectedDate by observable("")
    internal var selectedHeadline by observable("")
    internal var selectedSub by observable("")
    internal var selectedVs by observable("")
    internal val weeks: ObservableList<CalendarWeekRow> by observableList()
    internal val heatCells: ObservableList<HeatCellVm> by observableList()
    internal val legend: ObservableList<String> by observableList()
    internal val detailEvents: ObservableList<String> by observableList()
    internal val detailCodes: ObservableList<String> by observableList()
    internal var selectedSnap: CalendarDaySnapshot? = null
    internal val dayOverlay = Overlay(this)
    internal val press = PressState(this)
    private var cached = emptyList<CalendarDaySnapshot>()
    private var minMonth = CivilDate(2026, 9, 1)
    private var maxMonth = CivilDate(2026, 9, 1)

    override fun didInit() {
        super.didInit()
        reload()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        reload()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); flexDirectionColumn(); backgroundColor(0xFFF4F7FB) }
                pageTitleBar(ctx, "盈亏日历", ctx.subtitle, { ctx.loading }) { ctx.reload() }
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true); padding(left = 16f, right = 16f, bottom = 24f) }
                    vif({ !ctx.hasHoldings && ctx.heatCells.isEmpty() }) { calendarEmpty(ctx) }
                    vif({ ctx.hasHoldings || ctx.heatCells.isNotEmpty() }) {
                        calendarStats(ctx)
                        calendarMonthNav(ctx)
                        calendarWeekHead()
                        vfor({ ctx.weeks }) { week -> calendarWeekRowView(ctx, week) }
                        vif({ ctx.legend.isNotEmpty() }) { calendarLegend(ctx) }
                        calendarHeat(ctx)
                        Text {
                            attr {
                                text("颜色越深，当天盈亏幅度越大。点格子看哪只股票贡献了多少。除权/财报需要独立数据源，当前行情库没有这两张表时不会出现标记。")
                                fontSize(11f)
                                color(0xFF929CAB)
                                margin(top = 14f, bottom = 8f)
                                lineHeight(17f)
                            }
                        }
                    }
                }
                vif({ ctx.dayOverlay.isVisible }) { calendarDaySheet(ctx) }
            }
        }
    }

    internal fun reload() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            try {
                val pack = pageResult {
                    HoldingCalendar.syncQuietly()
                    Triple(
                        HoldingCalendar.days(),
                        WatchStore.list().any { it.shares > 0 },
                        runCatching { StockDb.latestTradeDate() }.getOrDefault(""),
                    )
                }
                cached = pack.first
                hasHoldings = pack.second
                val trade = CivilDate.parse(pack.third)
                val first = cached.firstOrNull()?.let { CivilDate.parse(it.date) }
                val last = cached.lastOrNull()?.let { CivilDate.parse(it.date) } ?: trade
                minMonth = (first ?: trade ?: CivilDate(2026, 9, 1)).startOfMonth()
                maxMonth = (last ?: CivilDate(2026, 9, 1)).startOfMonth()
                if (cached.isEmpty()) {
                    val seed = trade ?: CivilDate(2026, 9, 1)
                    viewYear = seed.year
                    viewMonth = seed.month
                } else {
                    val showing = CivilDate(viewYear, viewMonth, 1)
                    if (showing < minMonth || showing > maxMonth) {
                        viewYear = maxMonth.year
                        viewMonth = maxMonth.month
                    }
                }
                rebuild()
            } finally {
                loading = false
            }
        }
    }

    internal fun shiftMonth(delta: Int) {
        val next = CivilDate(viewYear, viewMonth, 1).plusMonths(delta).startOfMonth()
        if (next < minMonth || next > maxMonth) return
        viewYear = next.year
        viewMonth = next.month
        rebuild()
    }

    internal fun openDay(cell: CalendarCellVm) {
        if (!cell.inMonth) return
        val snap = cell.snapshot
        selectedDate = cell.date
        selectedSnap = snap
        detailCodes.clear()
        detailEvents.clear()
        if (snap == null) {
            selectedHeadline = "这一天还没有持仓记录"
            selectedSub = "日历只从接入后的交易日往后累，不回填历史。"
            selectedVs = ""
        } else {
            selectedHeadline = compactPnl(snap.dayPnl)
            selectedSub = "市值 ${fmt2(snap.marketValue)}  ·  当日 ${fmtSignedPct(snap.dayPnlPct)}"
            selectedVs = when (snap.beatHs300) {
                true -> "沪深300 ${fmtSignedPct(snap.hs300Pct ?: 0.0)}  ·  跑赢大盘"
                false -> "沪深300 ${fmtSignedPct(snap.hs300Pct ?: 0.0)}  ·  跑输大盘"
                null -> if (snap.hs300Pct == null) "当日沪深300数据暂缺" else "与沪深300持平"
            }
            detailCodes.addAll(snap.holdings.map { it.code })
            detailEvents.addAll(snap.events.map { it.label }.distinct())
        }
        dayOverlay.show()
    }

    internal fun lineOf(code: String) = selectedSnap?.holdings?.firstOrNull { it.code == code }

    internal fun openStock(code: String) {
        dayOverlay.hide()
        val params = JSONObject()
        params.put("code", code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("stock_detail", params)
    }

    internal fun goWatchlist() {
        openModule(AppRoutes.WATCHLIST)
    }

    private fun rebuild() {
        monthTitle = "${viewYear}年${viewMonth}月"
        val prefix = viewYear.toString().padStart(4, '0') + "-" + viewMonth.toString().padStart(2, '0')
        val maxAbs = cached.maxOfOrNull { abs(it.dayPnl) } ?: 0.0
        val stats = calendarStatsOf(cached, prefix)
        monthPnlText = if (stats.monthDays == 0) "暂无记录" else compactPnl(stats.monthPnl)
        monthPnlColor = StockColors.byChange(stats.monthPnl)
        winRateText = if (stats.winDays + stats.lossDays == 0) {
            "--"
        } else {
            fmt2(stats.winRate) + "%  ·  " + stats.winDays + "胜" + stats.lossDays + "负"
        }
        streakText = when {
            stats.streak > 0 -> "连盈 ${stats.streak} 天"
            stats.streak < 0 -> "连亏 ${-stats.streak} 天"
            else -> "暂无连胜"
        }
        extremaText = if (cached.isEmpty()) "--" else "最大盈 ${compactPnl(stats.maxWin)}  /  最大亏 ${compactPnl(stats.maxLoss)}"
        beatText = when {
            stats.monthDays == 0 -> "对照沪深300，避免躺赢误判"
            stats.beatDays + stats.loseDays == 0 -> "本月暂无大盘对照"
            else -> "本月跑赢 ${stats.beatDays} 天 · 跑输 ${stats.loseDays} 天"
        }
        val current = CivilDate(viewYear, viewMonth, 1)
        canPrev = current > minMonth
        canNext = current < maxMonth
        val byDate = cached.associateBy { it.date }
        val grid = monthCells(viewYear, viewMonth, byDate, maxAbs)
        weeks.clear()
        weeks.addAll(grid.mapIndexed { i, row -> CalendarWeekRow("w$i-${row.first().date}", row) })
        heatCells.clear()
        heatCells.addAll(heatStrip(cached, maxAbs))
        val kinds = cached.filter { it.date.startsWith(prefix) }.flatMap { it.events }.map { it.kind }.toSet()
        legend.clear()
        legend.addAll(kinds.map { eventLegend(it) }.filter { it.isNotEmpty() }.distinct())
        subtitle = if (cached.isEmpty()) {
            if (hasHoldings) "等行情更新后会出现第一笔记录，不回填历史。" else "先在自选里填持仓，之后每天自动记。"
        } else {
            "已记录 ${cached.size} 个交易日 · 只累积接入后的数据"
        }
    }
}

private fun eventLegend(kind: String): String = when (kind) {
    CAL_EVENT_LIMIT_UP -> "涨停"
    CAL_EVENT_LIMIT_DOWN -> "跌停"
    CAL_EVENT_ALERT -> "提醒"
    CAL_EVENT_DIVIDEND -> "除权"
    CAL_EVENT_EARNINGS -> "财报"
    else -> ""
}

private fun eventDotColor(kind: String): Long = when (kind) {
    CAL_EVENT_LIMIT_UP -> StockColors.UP
    CAL_EVENT_LIMIT_DOWN -> StockColors.DOWN
    CAL_EVENT_ALERT -> 0xFFA56100
    CAL_EVENT_DIVIDEND -> 0xFF0E67D1
    CAL_EVENT_EARNINGS -> 0xFF6650A4
    else -> 0xFF9AA3AF
}

private fun ViewContainer<*, *>.calendarEmpty(ctx: HoldingCalendarPage) {
    View {
        attr {
            marginTop(18f)
            padding(22f)
            borderRadius(16f)
            backgroundColor(Color.WHITE)
            alignItems(FlexAlign.CENTER)
        }
        Text { attr { text("还没有可记的持仓"); fontSize(16f); fontWeightBold(); color(0xFF24364D) } }
        Text {
            attr {
                text("在自选里填股数和成本。之后每次行情更新会自动记下当天市值和盈亏，日历不回填历史。")
                fontSize(12f)
                lineHeight(19f)
                color(0xFF7F8998)
                marginTop(8f)
                textAlignCenter()
            }
        }
        View {
            attr {
                marginTop(16f)
                height(44f)
                padding(left = 22f, right = 22f)
                borderRadius(22f)
                allCenter()
                backgroundColor(0xFF0E67D1)
                pressedScale(ctx.press, "cal_empty", normal = 1f, pressed = 0.97f)
                accessibility("去自选设置持仓")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event {
                pressFeedback(ctx.press, "cal_empty")
                click {
                    ctx.press.releaseAll()
                    ctx.goWatchlist()
                }
            }
            Text { attr { text("去自选设置持仓"); fontSize(14f); fontWeightBold(); color(Color.WHITE) } }
        }
    }
}

private fun ViewContainer<*, *>.calendarStats(ctx: HoldingCalendarPage) {
    View {
        attr { marginTop(14f); padding(16f); borderRadius(18f); backgroundColor(0xFF0B2B50) }
        Text { attr { text("本月盈亏"); fontSize(12f); color(0xFF9EC8F5) } }
        Text { attr { text(ctx.monthPnlText); fontSize(28f); fontWeightBold(); color(ctx.monthPnlColor); marginTop(6f) } }
        Text { attr { text(ctx.beatText); fontSize(12f); color(0xFFC7D8EA); marginTop(6f) } }
        View {
            attr { flexDirectionRow(); marginTop(16f) }
            calendarStatChip("日胜率", { ctx.winRateText })
            calendarStatChip("连盈 / 连亏", { ctx.streakText })
        }
        Text { attr { text(ctx.extremaText); fontSize(11f); color(0xFF9EB2C7); marginTop(10f) } }
    }
}

private fun ViewContainer<*, *>.calendarStatChip(label: String, value: () -> String) {
    View {
        attr { flex(1f) }
        Text { attr { text(label); fontSize(11f); color(0xFF9EB2C7) } }
        Text { attr { text(value()); fontSize(14f); fontWeightBold(); color(Color.WHITE); marginTop(4f) } }
    }
}

private fun ViewContainer<*, *>.calendarMonthNav(ctx: HoldingCalendarPage) {
    View {
        attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); margin(top = 16f, bottom = 8f) }
        View {
            attr {
                size(44f, 44f)
                allCenter()
                accessibility("上一月")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(ctx.canPrev, false)
            }
            event { click { if (ctx.canPrev) ctx.shiftMonth(-1) } }
            Text { attr { text("‹"); fontSize(26f); color(if (ctx.canPrev) 0xFF172A43 else 0xFFC5CAD1) } }
        }
        View {
            attr { flex(1f); alignItems(FlexAlign.CENTER) }
            Text { attr { text(ctx.monthTitle); fontSize(17f); fontWeightBold(); color(0xFF14263D) } }
        }
        View {
            attr {
                size(44f, 44f)
                allCenter()
                accessibility("下一月")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(ctx.canNext, false)
            }
            event { click { if (ctx.canNext) ctx.shiftMonth(1) } }
            Text { attr { text("›"); fontSize(26f); color(if (ctx.canNext) 0xFF172A43 else 0xFFC5CAD1) } }
        }
    }
}

private fun ViewContainer<*, *>.calendarWeekHead() {
    View {
        attr { flexDirectionRow(); marginBottom(4f) }
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
            View {
                attr { flex(1f); allCenter(); height(22f) }
                Text { attr { text(d); fontSize(11f); color(if (d == "六" || d == "日") 0xFFB0B7C0 else 0xFF8A94A3) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.calendarWeekRowView(ctx: HoldingCalendarPage, week: CalendarWeekRow) {
    View {
        attr { flexDirectionRow(); marginBottom(4f) }
        week.days.forEach { day -> calendarDayCell(ctx, day) }
    }
}

private fun ViewContainer<*, *>.calendarDayCell(ctx: HoldingCalendarPage, day: CalendarCellVm) {
    val tag = "cal:${day.date}"
    val selected = ctx.selectedDate == day.date && ctx.dayOverlay.isVisible
    View {
        attr {
            flex(1f)
            height(68f)
            margin(1.5f)
            borderRadius(8f)
            backgroundColor(day.color)
            alignItems(FlexAlign.CENTER)
            padding(top = 5f, bottom = 4f)
            if (selected) border(Border(1.5f, BorderStyle.SOLID, Color(0xFF0E67D1)))
            pressedScale(ctx.press, tag, normal = 1f, pressed = 0.96f)
            val vs = if (day.vsHs300.isNotEmpty()) "，${if (day.vsHs300 == "赢") "跑赢" else "跑输"}沪深300" else ""
            val pnl = if (day.pnlText.isNotEmpty()) "，盈亏 ${day.pnlText}" else "，无记录"
            accessibility("${day.date}$pnl$vs")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(day.inMonth, false)
        }
        event {
            pressFeedback(ctx.press, tag)
            click {
                ctx.press.releaseAll()
                ctx.openDay(day)
            }
        }
        Text {
            attr {
                text(day.dayNum.toString())
                fontSize(12f)
                fontWeightBold()
                color(if (day.inMonth) 0xFF1C3048 else 0xFFC5CAD1)
            }
        }
        Text {
            attr {
                text(if (day.inMonth) day.pnlText else "")
                fontSize(9f)
                color(if (day.pnl != null) StockColors.byChange(day.pnl) else 0xFFB0B7C0)
                marginTop(2f)
            }
        }
        vif({ day.vsHs300.isNotEmpty() && day.inMonth }) {
            Text {
                attr {
                    text(day.vsHs300)
                    fontSize(8f)
                    color(if (day.vsHs300 == "赢") StockColors.UP else StockColors.DOWN)
                    marginTop(1f)
                }
            }
        }
        vif({ day.events.isNotEmpty() && day.inMonth }) {
            View {
                attr { flexDirectionRow(); marginTop(2f); alignItems(FlexAlign.CENTER) }
                day.events.take(3).forEach { ev ->
                    View { attr { size(5f, 5f); borderRadius(2.5f); backgroundColor(eventDotColor(ev.kind)); margin(1f) } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.calendarLegend(ctx: HoldingCalendarPage) {
    View {
        attr { flexDirectionRow(); flexWrapWrap(); marginTop(8f); alignItems(FlexAlign.CENTER) }
        Text { attr { text("标记  "); fontSize(11f); color(0xFF8A94A3) } }
        vfor({ ctx.legend }) { label ->
            Text { attr { text(label + "  "); fontSize(11f); color(0xFF5A6B82) } }
        }
    }
}

private fun ViewContainer<*, *>.calendarHeat(ctx: HoldingCalendarPage) {
    vif({ ctx.heatCells.isNotEmpty() }) {
        View {
            attr { marginTop(18f); padding(14f); borderRadius(16f); backgroundColor(Color.WHITE) }
            Text { attr { text("节奏热力图"); fontSize(15f); fontWeightBold(); color(0xFF172A43) } }
            Text { attr { text("自首次记录起，红涨绿跌，深浅表示幅度"); fontSize(11f); color(0xFF8A94A3); marginTop(3f) } }
            View {
                attr { flexDirectionRow(); flexWrapWrap(); marginTop(10f) }
                vfor({ ctx.heatCells }) { cell ->
                    View {
                        attr {
                            size(11f, 11f)
                            margin(1.5f)
                            borderRadius(2f)
                            backgroundColor(cell.color)
                            accessibility(if (cell.hasData) cell.date else "${cell.date}无记录")
                        }
                    }
                }
            }
            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(10f) }
                Text { attr { text("亏"); fontSize(10f); color(0xFF8A94A3); marginRight(6f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.down(0x48)); marginRight(3f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.down(0xE6)); marginRight(8f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(0xFFEEF1F4); marginRight(8f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.up(0x48)); marginRight(3f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.up(0xE6)); marginRight(6f) } }
                Text { attr { text("盈"); fontSize(10f); color(0xFF8A94A3) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.calendarDaySheet(ctx: HoldingCalendarPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x88000000)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.dayOverlay.hide() } }
        View {
            attr {
                width(ctx.pagerData.pageViewWidth - 40f)
                padding(18f)
                borderRadius(16f)
                backgroundColor(Color.WHITE)
                overlayEnterExit(ctx.dayOverlay)
            }
            Text { attr { text(ctx.selectedDate); fontSize(12f); color(0xFF8A94A3) } }
            Text { attr { text(ctx.selectedHeadline); fontSize(26f); fontWeightBold(); color(0xFF14263D); marginTop(4f) } }
            Text { attr { text(ctx.selectedSub); fontSize(12f); color(0xFF5A6B82); marginTop(4f) } }
            vif({ ctx.selectedVs.isNotEmpty() }) {
                Text { attr { text(ctx.selectedVs); fontSize(12f); fontWeightBold(); color(0xFF0E67D1); marginTop(6f) } }
            }
            vif({ ctx.detailEvents.isNotEmpty() }) {
                vfor({ ctx.detailEvents }) { label ->
                    Text { attr { text("· $label"); fontSize(12f); color(0xFF8B4C12); marginTop(4f) } }
                }
            }
            vif({ ctx.detailCodes.isNotEmpty() }) {
                Text { attr { text("盈亏构成"); fontSize(13f); fontWeightBold(); color(0xFF172A43); marginTop(14f) } }
                vfor({ ctx.detailCodes }) { code ->
                    calendarHoldingLine(ctx, code)
                }
            }
            View {
                attr {
                    marginTop(16f)
                    height(44f)
                    allCenter()
                    borderRadius(12f)
                    backgroundColor(0xFFF0F2F5)
                    accessibility("关闭")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event { click { ctx.dayOverlay.hide() } }
                Text { attr { text("关闭"); fontSize(13f); color(0xFF697586) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.calendarHoldingLine(ctx: HoldingCalendarPage, code: String) {
    val line = ctx.lineOf(code) ?: return
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(8f)
            padding(10f)
            borderRadius(10f)
            backgroundColor(0xFFF4F7FB)
            accessibility("打开${line.name}详情，当日盈亏 ${compactPnl(line.dayPnl)}")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.openStock(code) } }
        View {
            attr { flex(1f) }
            Text { attr { text(line.name); fontSize(14f); fontWeightBold(); color(0xFF1C3048) } }
            Text {
                attr {
                    text(
                        "${line.code}  ${fmtSignedPct(line.changePercent)}" +
                            if (line.limit.isNotEmpty()) "  ${if (line.limit == "up") "涨停" else "跌停"}" else ""
                    )
                    fontSize(11f)
                    color(0xFF8993A1)
                    marginTop(2f)
                }
            }
        }
        Text {
            attr {
                text(compactPnl(line.dayPnl))
                fontSize(15f)
                fontWeightBold()
                color(StockColors.byChange(line.dayPnl))
            }
        }
    }
}
