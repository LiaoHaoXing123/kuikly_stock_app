package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ui.component.Overlay
import com.kuikly.stock.ui.component.PressState
import com.kuikly.stock.ui.component.overlayEnterExit
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedScale
import com.kuikly.stock.data.CAL_EVENT_ALERT
import com.kuikly.stock.data.CAL_EVENT_DIVIDEND
import com.kuikly.stock.data.CAL_EVENT_EARNINGS
import com.kuikly.stock.data.CAL_EVENT_LIMIT_DOWN
import com.kuikly.stock.data.CAL_EVENT_LIMIT_UP
import com.kuikly.stock.data.CalendarEventMark
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
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.coroutines.delay
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
import com.tencent.kuikly.core.views.RefreshView
import com.tencent.kuikly.core.views.RefreshViewState
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.abs
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.REFRESH_SPIN_STEP_MS
import com.kuikly.stock.ui.component.emptyStatePanel
import com.kuikly.stock.ui.component.openModule
import com.kuikly.stock.ui.component.pageTitleBar
import com.kuikly.stock.ui.component.pullRefreshLabel
import com.kuikly.stock.ui.component.pullToRefresh
import com.kuikly.stock.ui.theme.AppColor

internal data class CalendarWeekRow(val key: String, val days: List<CalendarCellVm>)

/** 单日最多画几个事件圆点，超出的折成 +N。 */
private const val DAY_EVENT_DOT_MAX = 3

/** 切月横滑的位移量，单位是月历网格自身宽度的比例。 */
private const val MONTH_SLIDE_RATIO = 0.14f

/** 切月动画时长。滑出与滑入共用一条曲线，合计两段。 */
private const val MONTH_SLIDE_MS = 150

private val MONTH_SLIDE_ANIM: Animation = Animation.easeOut(MONTH_SLIDE_MS / 1000f)

@Page(AppRoutes.CALENDAR)
class HoldingCalendarPage : BasePager() {

    internal var loading by observable(false)

    /** 下拉刷新头的当前状态（见 Refresh.kt）。 */
    internal var pullState by observable(RefreshViewState.IDLE)

    /** 刷新头引用。取数结束（含早退与失败）都要用它 endRefresh()，否则指示器一直转。 */
    internal var pullRefreshRef: ViewRef<RefreshView>? = null
    internal var hasHoldings by observable(false)
    internal var monthTitle by observable("盈亏日历")
    internal var subtitle by observable("结合当前持仓，算出每天盈亏")
    /** 切月位移的起点，写入不带动画：用它把内容瞬移到对侧，再交给 [monthReveal] 滑进来。 */
    private var monthSlideBase by observable(0f)

    /** 切月进场进度：0 = 贴在侧边且透明，1 = 落位。动画挂在这个 observable 上。 */
    internal var monthReveal by observable(1f)

    /** 切月动画进行中，期间忽略重复点击。 */
    private var monthSliding = false

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
    private var externalEvents = emptyMap<String, MutableList<CalendarEventMark>>()
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
                attr { flex(1f); flexDirectionColumn(); backgroundColor(AppColor.BG) }
                pageTitleBar(ctx, "盈亏日历", ctx.subtitle, { ctx.loading }) { ctx.reload() }
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true); padding(left = 16f, right = 16f, bottom = 24f) }
                    // 必须是 Scroller 的第一个子视图：RefreshView 取 Scroller 用的是 parent.parent
                    pullToRefresh(
                        bind = { ctx.pullRefreshRef = it },
                        label = { pullRefreshLabel(ctx.pullState, ctx.loading) },
                        onStateChange = { ctx.pullState = it },
                        onRefresh = { ctx.reload() },
                        spin = ctx.refreshSpin,
                        spinning = { ctx.loading },
                    )
                    vif({ !ctx.hasHoldings && ctx.heatCells.isEmpty() }) { calendarEmpty(ctx) }
                    vif({ ctx.hasHoldings || ctx.heatCells.isNotEmpty() }) {
                        calendarStats(ctx)
                        calendarMonthNav(ctx)
                        View {
                            attr {
                                flexDirectionColumn()
                                animate(MONTH_SLIDE_ANIM, ctx.monthReveal)
                                opacity(ctx.monthReveal)
                                transform(
                                    translate = Translate(
                                        percentageX = ctx.monthSlideBase * (1f - ctx.monthReveal),
                                    ),
                                )
                            }
                            calendarWeekHead()
                            vfor({ ctx.weeks }) { week -> calendarWeekRowView(ctx, week) }
                        }
                        View { attr { height(1f); backgroundColor(AppColor.DIVIDER); marginTop(20f) } }
                        vif({ ctx.legend.isNotEmpty() }) { calendarLegend(ctx) }
                        calendarHeat(ctx)
                        Text {
                            attr {
                                text("颜色越深，当天盈亏幅度越大。点格子看哪只股票贡献了多少。彩色圆点标记涨跌停、提醒、除权和财报日。")
                                fontSize(11f)
                                color(AppColor.TEXT_MUTED)
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
        if (loading) {
            // 已有请求在跑：立刻收掉刷新头，否则它会一直转
            pullRefreshRef?.view?.endRefresh()
            return
        }
        loading = true
        // 刷新头箭头开始转；结束由 loading 翻 false 自然停（不能用 repeatForever，见 Motion.kt）
        refreshSpin.loop(REFRESH_SPIN_STEP_MS) { loading }
        lifecycleScope.launch {
            try {
                val pack = pageResult {
                    HoldingCalendar.syncQuietly()
                    // 用当前持仓 + 日线收盘价历史补齐最近的交易日，让用户能看到「上一天亏了多少」，
                    // 而不是只有接入当天一个格子。只补缺失日期，不覆盖 sync 记下的真实快照。
                    HoldingCalendar.backfillQuietly()
                    Triple(
                        HoldingCalendar.days(),
                        WatchStore.list().any { it.shares > 0 },
                        runCatching { StockDb.latestTradeDate() }.getOrDefault(""),
                    )
                }
                cached = pack.first
                hasHoldings = pack.second
                // 读取当前持仓的除权/财报事件，独立于盈亏快照显示
                val evtMap = HashMap<String, MutableList<CalendarEventMark>>()
                for (h in WatchStore.list()) {
                    if (h.shares <= 0.0 || !h.shares.isFinite()) continue
                    runCatching { StockDb.dividendEvents(h.code) }.getOrDefault(emptyList()).forEach { e ->
                        if (e.date.isNotEmpty()) evtMap.getOrPut(e.date) { mutableListOf() }.add(e)
                    }
                    runCatching { StockDb.earningsEvents(h.code) }.getOrDefault(emptyList()).forEach { e ->
                        if (e.date.isNotEmpty()) evtMap.getOrPut(e.date) { mutableListOf() }.add(e)
                    }
                }
                externalEvents = evtMap
                val trade = CivilDate.parse(pack.third)
                val first = cached.firstOrNull()?.let { CivilDate.parse(it.date) }
                val last = cached.lastOrNull()?.let { CivilDate.parse(it.date) } ?: trade
                val evtDates = externalEvents.keys.mapNotNull { CivilDate.parse(it) }.sorted()
                val evtFirst = evtDates.firstOrNull()
                val evtLast = evtDates.lastOrNull()
                val allFirst = listOfNotNull(first, evtFirst).minOrNull()
                val allLast = listOfNotNull(last, evtLast).maxOrNull()
                minMonth = (allFirst ?: trade ?: CivilDate(2026, 9, 1)).startOfMonth()
                maxMonth = (allLast ?: CivilDate(2026, 9, 1)).startOfMonth()
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
                pullRefreshRef?.view?.endRefresh()
            }
        }
    }

    /**
     * 切月：旧内容朝 delta 方向滑出淡出，换好月份后新内容从对侧滑入。
     *
     * 两段共用 [monthReveal] 一条动画，靠 [monthSlideBase] 的「无动画写入」换边——
     * 换边那一刻 opacity 正好是 0，位移跳变看不见。
     */
    internal fun shiftMonth(delta: Int) {
        val next = CivilDate(viewYear, viewMonth, 1).plusMonths(delta).startOfMonth()
        if (next < minMonth || next > maxMonth) return
        if (monthSliding) return
        monthSliding = true
        monthSlideBase = delta * MONTH_SLIDE_RATIO
        monthReveal = 0f
        lifecycleScope.launch {
            delay(MONTH_SLIDE_MS)
            viewYear = next.year
            viewMonth = next.month
            rebuild()
            monthSlideBase = -delta * MONTH_SLIDE_RATIO
            monthReveal = 1f
            delay(MONTH_SLIDE_MS)
            monthSliding = false
        }
    }

    internal fun openDay(cell: CalendarCellVm) {
        if (!cell.inMonth) return
        val snap = cell.snapshot
        selectedDate = cell.date
        selectedSnap = snap
        detailCodes.clear()
        detailEvents.clear()
        val extEvts = externalEvents[cell.date].orEmpty()
        if (snap == null) {
            if (extEvts.isNotEmpty()) {
                selectedHeadline = extEvts.joinToString("、") { it.label }
                selectedSub = "当天无持仓盈亏记录，但有以下事件：" + extEvts.joinToString("、") { eventLegend(it.kind) }
                selectedVs = ""
                detailEvents.addAll(extEvts.map { it.label }.distinct())
            } else {
                selectedHeadline = "这一天没有持仓盈亏记录"
                selectedSub = "非交易日，或当时行情库没有这天的日线数据。"
                selectedVs = ""
            }
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
        val grid = monthCells(viewYear, viewMonth, byDate, maxAbs, externalEvents, AppColor.SURFACE_ALT, AppColor.SURFACE_SOFT)
        weeks.clear()
        weeks.addAll(grid.mapIndexed { i, row -> CalendarWeekRow("w$i-${row.first().date}", row) })
        heatCells.clear()
        heatCells.addAll(heatStrip(cached, maxAbs, AppColor.TRACK))
        val snapKinds = cached.filter { it.date.startsWith(prefix) }.flatMap { it.events }.map { it.kind }.toSet()
        val extKinds = externalEvents.entries.filter { it.key.startsWith(prefix) }.flatMap { it.value }.map { it.kind }.toSet()
        val kinds = snapKinds + extKinds
        legend.clear()
        legend.addAll(kinds.map { eventLegend(it) }.filter { it.isNotEmpty() }.distinct())
        subtitle = if (cached.isEmpty()) {
            if (hasHoldings) "正在按当前持仓补齐最近交易日…" else "先在自选里填持仓，就能看到每天盈亏。"
        } else {
            "已记录 ${cached.size} 个交易日 · 结合当前持仓估算历史"
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
    CAL_EVENT_ALERT -> AppColor.WARNING_TEXT
    CAL_EVENT_DIVIDEND -> AppColor.PRIMARY
    CAL_EVENT_EARNINGS -> AppColor.VIOLET
    else -> AppColor.TEXT_MUTED
}

private fun ViewContainer<*, *>.calendarEmpty(ctx: HoldingCalendarPage) {
    emptyStatePanel(
        title = "还没有可记的持仓",
        message = "在自选里填股数和成本。日历会结合当前持仓和日线收盘价，算出最近每个交易日的盈亏。",
        actionLabel = "去自选设置持仓",
        press = ctx.press,
        actionTag = "cal_empty",
        onAction = { ctx.goWatchlist() },
    )
}

private fun ViewContainer<*, *>.calendarStats(ctx: HoldingCalendarPage) {
    View {
        attr {
            marginTop(14f)
            padding(16f)
            borderRadius(18f)
            // 纯色深蓝压在浅蓝页底上边界太硬，改成右下角略微提亮的斜向渐变
            backgroundLinearGradient(
                Direction.TO_BOTTOM_RIGHT,
                ColorStop(Color(AppColor.INK_PANEL), 0f),
                ColorStop(Color(AppColor.INK_PANEL_SOFT), 1f),
            )
        }
        Text { attr { text("本月盈亏"); fontSize(12f); color(AppColor.ON_DARK_ACCENT) } }
        Text { attr { text(ctx.monthPnlText); fontSize(28f); fontWeightBold(); color(ctx.monthPnlColor); marginTop(6f) } }
        Text { attr { text(ctx.beatText); fontSize(12f); color(AppColor.ON_DARK_SUB); marginTop(6f) } }
        View {
            attr { flexDirectionRow(); marginTop(16f) }
            calendarStatChip("日胜率", { ctx.winRateText })
            calendarStatChip("连盈 / 连亏", { ctx.streakText })
        }
        Text { attr { text(ctx.extremaText); fontSize(11f); color(AppColor.ON_DARK_MUTED); marginTop(10f) } }
    }
}

private fun ViewContainer<*, *>.calendarStatChip(label: String, value: () -> String) {
    View {
        attr { flex(1f) }
        Text { attr { text(label); fontSize(11f); color(AppColor.ON_DARK_MUTED) } }
        Text { attr { text(value()); fontSize(14f); fontWeightBold(); color(Color(AppColor.ON_DARK)); marginTop(4f) } }
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
            Text { attr { text("‹"); fontSize(26f); color(if (ctx.canPrev) AppColor.TEXT_STRONG else AppColor.DISABLED) } }
        }
        View {
            attr { flex(1f); alignItems(FlexAlign.CENTER) }
            Text { attr { text(ctx.monthTitle); fontSize(17f); fontWeightBold(); color(AppColor.TEXT_STRONG) } }
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
            Text { attr { text("›"); fontSize(26f); color(if (ctx.canNext) AppColor.TEXT_STRONG else AppColor.DISABLED) } }
        }
    }
}

private fun ViewContainer<*, *>.calendarWeekHead() {
    View {
        attr { flexDirectionRow(); marginBottom(4f) }
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
            View {
                attr { flex(1f); allCenter(); height(22f) }
                Text { attr { text(d); fontSize(11f); color(if (d == "六" || d == "日") AppColor.DISABLED else AppColor.TEXT_SUB) } }
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
            if (selected) border(Border(1.5f, BorderStyle.SOLID, Color(AppColor.PRIMARY)))
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
                color(if (day.inMonth) AppColor.TEXT else AppColor.DISABLED)
            }
        }
        Text {
            attr {
                text(if (day.inMonth) day.pnlText else "")
                fontSize(10f)
                fontWeightBold()
                color(
                    if (day.pnl != null) {
                        if (day.pnl > 0) AppColor.TEXT_INK else AppColor.ON_DARK
                    } else AppColor.DISABLED
                )
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
                day.events.take(DAY_EVENT_DOT_MAX).forEach { ev ->
                    View { attr { size(5f, 5f); borderRadius(2.5f); backgroundColor(eventDotColor(ev.kind)); margin(1f) } }
                }
                vif({ day.events.size > DAY_EVENT_DOT_MAX }) {
                    Text {
                        attr {
                            text("+${day.events.size - DAY_EVENT_DOT_MAX}")
                            fontSize(8f)
                            color(AppColor.TEXT_SUB)
                            marginLeft(1f)
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.calendarLegend(ctx: HoldingCalendarPage) {
    View {
        attr { flexDirectionRow(); flexWrapWrap(); marginTop(8f); alignItems(FlexAlign.CENTER) }
        Text { attr { text("标记  "); fontSize(11f); color(AppColor.TEXT_SUB) } }
        vfor({ ctx.legend }) { label ->
            Text { attr { text(label + "  "); fontSize(11f); color(AppColor.TEXT_SUB_DEEP) } }
        }
    }
}

private fun ViewContainer<*, *>.calendarHeat(ctx: HoldingCalendarPage) {
    vif({ ctx.heatCells.isNotEmpty() }) {
        View {
            attr { marginTop(14f); padding(14f); borderRadius(16f); backgroundColor(AppColor.SURFACE) }
            Text { attr { text("节奏热力图"); fontSize(15f); fontWeightBold(); color(AppColor.TEXT_STRONG) } }
            Text { attr { text("自首次记录起，红涨绿跌，深浅表示幅度"); fontSize(11f); color(AppColor.TEXT_SUB); marginTop(3f) } }
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
                Text { attr { text("亏"); fontSize(10f); color(AppColor.TEXT_SUB); marginRight(6f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.down(0x48)); marginRight(3f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.down(0xE6)); marginRight(8f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(AppColor.SURFACE_ALT); marginRight(8f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.up(0x48)); marginRight(3f) } }
                View { attr { size(10f, 10f); borderRadius(2f); backgroundColor(StockColors.up(0xE6)); marginRight(6f) } }
                Text { attr { text("盈"); fontSize(10f); color(AppColor.TEXT_SUB) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.calendarDaySheet(ctx: HoldingCalendarPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(AppColor.SCRIM)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.dayOverlay.hide() } }
        View {
            attr {
                width(ctx.pagerData.pageViewWidth - 40f)
                padding(18f)
                borderRadius(16f)
                backgroundColor(AppColor.SURFACE)
                overlayEnterExit(ctx.dayOverlay)
            }
            Text { attr { text(ctx.selectedDate); fontSize(12f); color(AppColor.TEXT_SUB) } }
            Text { attr { text(ctx.selectedHeadline); fontSize(26f); fontWeightBold(); color(AppColor.TEXT_STRONG); marginTop(4f) } }
            Text { attr { text(ctx.selectedSub); fontSize(12f); color(AppColor.TEXT_SUB_DEEP); marginTop(4f) } }
            vif({ ctx.selectedVs.isNotEmpty() }) {
                Text { attr { text(ctx.selectedVs); fontSize(12f); fontWeightBold(); color(AppColor.PRIMARY); marginTop(6f) } }
            }
            vif({ ctx.detailEvents.isNotEmpty() }) {
                vfor({ ctx.detailEvents }) { label ->
                    Text { attr { text("· $label"); fontSize(12f); color(AppColor.WARNING_TEXT_DEEP); marginTop(4f) } }
                }
            }
            vif({ ctx.detailCodes.isNotEmpty() }) {
                Text { attr { text("盈亏构成"); fontSize(13f); fontWeightBold(); color(AppColor.TEXT_STRONG); marginTop(14f) } }
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
                    backgroundColor(AppColor.BG_SOFT)
                    accessibility("关闭")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event { click { ctx.dayOverlay.hide() } }
                Text { attr { text("关闭"); fontSize(13f); color(AppColor.TEXT_SUB_DEEP) } }
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
            backgroundColor(AppColor.BG)
            accessibility("打开${line.name}详情，当日盈亏 ${compactPnl(line.dayPnl)}")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.openStock(code) } }
        View {
            attr { flex(1f) }
            Text { attr { text(line.name); fontSize(14f); fontWeightBold(); color(AppColor.TEXT) } }
            Text {
                attr {
                    text(
                        "${line.code}  ${fmtSignedPct(line.changePercent)}" +
                            if (line.limit.isNotEmpty()) "  ${if (line.limit == "up") "涨停" else "跌停"}" else ""
                    )
                    fontSize(11f)
                    color(AppColor.TEXT_SUB)
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
