package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.base.MountPulse
import com.kuikly.stock.base.NumberRoll
import com.kuikly.stock.base.Overlay
import com.kuikly.stock.base.overlayEnterExit
import com.kuikly.stock.base.PressState
import com.kuikly.stock.base.pressFeedback
import com.kuikly.stock.base.pressedScale
import com.kuikly.stock.base.skeletonBlock
import com.kuikly.stock.data.StockColors

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.*
import com.kuikly.stock.data.HoldingInput
import com.kuikly.stock.data.PriceAlertRule
import com.kuikly.stock.data.HoldingCalendar
import com.kuikly.stock.data.WatchHolding
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.data.describeAlertType
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import com.tencent.kuikly.core.coroutines.launch

@Page("watchlist")
class WatchlistPage : BasePager() {

    internal var rows: ObservableList<WatchRowData> by observableList()

    internal var isLoading by observable(false)

    /**
     * 持仓总览的四个数字（市值 / 盈亏 / 盈亏率 / 今日盈亏），刷新时滚动过渡。
     * 数字挤在一句话里，所以用 [NumberRoll] 整组滚，而不是各滚各的。
     */
    internal val summaryRoll = NumberRoll(
        this,
        initialText = "尚未设置持仓",
    ) { v ->
        if (v[0] <= 0) {
            "未设置持仓，收藏的股票仅作关注。"
        } else {
            "持仓市值 " + fmt2(v[0]) +
                "   持仓盈亏 " + signed2(v[1]) + " (" + signed2(v[2]) + "%)" +
                "   今日盈亏 " + signed2(v[3])
        }
    }

    /** 编辑弹窗：显隐 + 入场动画绑在一起。 */
    internal val editOverlay = Overlay(this)
    internal var editCode by observable("")
    internal var editName by observable("")
    internal var editSharesText by observable("")
    internal var editCostText by observable("")
    internal var editAlertType by observable(-1)
    internal var editThresholdText by observable("")
    internal var editRules: ObservableList<PriceAlertRule> by observableList()
    internal var editMessage by observable("")
    internal var saveMessage by observable("")

    internal var pullState by observable(RefreshViewState.IDLE)

    /** 按压态：空态出口按钮与保存按钮共用，页面唯一一份。 */
    internal val press = PressState(this)

    internal var pullRefreshRef: ViewRef<RefreshView>? = null

    override fun didInit() {
        super.didInit()
        reload()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        // 首屏 loading 在 didInit 就发起了（那时 body 还没构建，扫光无从谈起），这里补一次：
        //   - 已在加载中：reload() 会早退、不 bump，所以手动补一次，让扫光跟上骨架屏；
        //   - 已加载完：reload() 重新取数，它内部会 bump。
        // 两条路径都保证同一帧只写一次 observable——写两次的话两次属性会合并提交，
        // 第二次的目标值等于当前值，原生动画器就没有位移可插值了（扫光静默失效）。
        val stillLoading = isLoading
        reload()
        if (stillLoading) skeletonPulse.bump()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(0xFFF5F5F5)
                }
                watchlistNavBar(ctx)
                statusFeedback({ ctx.saveMessage }, { ctx.saveMessage.contains("失败") })
                vif({ ctx.rows.isNotEmpty() }) {
                    watchSummary(ctx)
                }
                Scroller {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        scrollEnable(true)
                    }
                    pullToRefresh(
                        bind = { ctx.pullRefreshRef = it },
                        label = { pullRefreshLabel(ctx.pullState, ctx.isLoading) },
                        onStateChange = { ctx.pullState = it },
                        onRefresh = { ctx.reload() },
                        spin = ctx.refreshSpin,
                        spinning = { ctx.isLoading },
                    )
                    vif({ ctx.isLoading && ctx.rows.isEmpty() }) {
                        watchlistLoadingView(ctx)
                    }
                    vif({ !ctx.isLoading && ctx.rows.isEmpty() }) {
                        watchlistEmptyView(ctx)
                    }
                    vif({ ctx.rows.isNotEmpty() }) {
                        vfor({ ctx.rows }) { row ->
                            watchlistRow(ctx, row)
                        }
                        View { attr { height(10f) } }
                    }
                }
                appBottomNav(ctx, AppRoutes.WATCHLIST)
                vif({ ctx.editOverlay.isVisible }) {
                    watchEditDialog(ctx)
                }
            }
        }
    }

    internal fun reload(showFeedback: Boolean = false) {
        if (isLoading) {
            // 已有请求在跑：立刻收掉刷新头，否则它会一直转
            pullRefreshRef?.view?.endRefresh()
            return
        }
        isLoading = true
        // 骨架屏刚由 vif 同步挂载，此刻拉起扫光才赶得上首帧
        skeletonPulse.bump()
        // 刷新头箭头开始转
        refreshSpin.loop(REFRESH_SPIN_STEP_MS) { isLoading }
        lifecycleScope.launch {
            try {
                val built = pageResult {
                    HoldingCalendar.syncQuietly()
                    val watch = WatchStore.list()
                    val built = mutableListOf<WatchRowData>()
                    for (h in watch) {
                        val row = buildRow(h)
                        if (row != null) built.add(row)
                    }
                    built
                }
                rows.clear()
                rows.addAll(built)
                summaryRoll.rollTo(*summarize(built))
                if (showFeedback) saveMessage = "自选已刷新，共 ${built.size} 只"
            } catch (e: Throwable) {
                saveMessage = "刷新失败，请重试"
            } finally {
                isLoading = false
                pullRefreshRef?.view?.endRefresh()
            }
        }
    }

    private suspend fun buildRow(h: WatchHolding): WatchRowData? {
        val d = try { StockRepository.loadStockDetail(h.code) } catch (e: Throwable) { null }
        val rt = d?.realtime
        val price = rt?.price
        val pct = rt?.changePercent ?: 0.0
        val change = rt?.change ?: 0.0
        val color = when {
            pct > 0 -> StockColors.UP
            pct < 0 -> StockColors.DOWN
            else -> 0xFF888888
        }
        val alerts = WatchStore.alertsOf(h.code).filter { it.enabled }
        val alertDesc = if (alerts.isNotEmpty()) "${alerts.size} 条 · " + alerts.joinToString(" / ") {
            describeAlertType(it.type) + " " + fmt2(it.threshold)
        } else ""
        val mv = if (price != null) price * h.shares else 0.0
        val posPnl = if (price != null) (price - h.cost) * h.shares else 0.0
        val pnlPct = if (h.cost > 0 && h.shares > 0) price?.let { (it - h.cost) / h.cost * 100.0 } ?: 0.0 else 0.0
        val todayPnl = if (h.shares > 0) change * h.shares else 0.0
        return WatchRowData(
            code = h.code,
            name = h.name.ifBlank { rt?.name ?: h.code },
            priceText = price?.let { fmt2(it) } ?: "-",
            pctText = if (price != null) fmtSignedPct(pct) else "-",
            pctColor = color,
            shares = h.shares,
            cost = h.cost,
            marketValue = mv,
            posPnl = posPnl,
            pnlPct = pnlPct,
            todayPnl = todayPnl,
            marketValueText = if (h.shares > 0) fmt2(mv) else "",
            todayPnlText = if (h.shares > 0) signed2(todayPnl) else "",
            pnlText = if (h.shares > 0) signed2(posPnl) else "",
            pnlPctText = if (h.shares > 0 && h.cost > 0) signed2(pnlPct) + "%" else "",
            alertDesc = alertDesc,
            hasAlert = alerts.isNotEmpty()
        )
    }

    /** 汇总为 [市值, 浮动盈亏, 盈亏率%, 今日盈亏]；格式化交给 [summaryRoll]。 */
    private fun summarize(items: List<WatchRowData>): DoubleArray {
        var mv = 0.0
        var today = 0.0
        var pos = 0.0
        var costSum = 0.0
        for (r in items) {
            if (r.shares <= 0) continue
            mv += r.marketValue
            pos += r.posPnl
            today += r.todayPnl
            costSum += r.cost * r.shares
        }
        val posPct = if (costSum > 0) pos / costSum * 100.0 else 0.0
        return doubleArrayOf(mv, pos, posPct, today)
    }

    internal fun openEdit(row: WatchRowData) {
        editCode = row.code
        editName = row.name
        editSharesText = trimNum(row.shares)
        editCostText = fmt2(row.cost)
        editRules.clear()
        editRules.addAll(WatchStore.alertsOf(row.code))
        editAlertType = -1
        editThresholdText = ""
        editMessage = ""
        editOverlay.show()
    }

    /** 关闭编辑弹窗（卸载与入场脉冲归位由 Overlay 一并处理）。 */
    internal fun dismissEdit() {
        editOverlay.hide()
    }

    internal fun saveEdit() {
        val input = HoldingInput.parse(editCode, editSharesText, editCostText, allowClear = true)
        if (input == null) {
            editMessage = "请输入有效股数和成本价；股数填0保留自选"
            return
        }
        val threshold = editThresholdText.trim().toDoubleOrNull()
        if (editAlertType >= 0 && (threshold == null || !threshold.isFinite() || threshold <= 0.0)) {
            editMessage = "提醒阈值需为有效正数"
            return
        }
        WatchStore.updateHolding(WatchHolding(editCode, editName, input.shares, input.cost))
        val alertSaved = if (editAlertType >= 0 && threshold != null) {
            WatchStore.upsertAlert(PriceAlertRule(editCode, editName, editAlertType, threshold, true))
        } else true
        dismissEdit()
        saveMessage = if (alertSaved) "已保存自选与提醒" else "提醒保存失败，请重试"
        reload()
    }

    internal fun toggleRule(rule: PriceAlertRule) {
        val saved = WatchStore.upsertAlert(rule.copy(enabled = !rule.enabled))
        editMessage = if (saved) "提醒已更新" else "提醒保存失败，请重试"
        refreshRules()
    }

    internal fun deleteRule(rule: PriceAlertRule) {
        val saved = WatchStore.removeAlert(rule)
        editMessage = if (saved) "已删除此规则" else "提醒删除失败，请重试"
        refreshRules()
    }

    private fun refreshRules() {
        editRules.clear()
        editRules.addAll(WatchStore.alertsOf(editCode))
        reload()
    }

    internal fun removeItem(code: String) {
        WatchStore.remove(code)
        reload()
    }

    private fun trimNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else fmt2(v)
}

internal data class WatchRowData(
    val code: String,
    val name: String,
    val priceText: String,
    val pctText: String,
    val pctColor: Long,
    val shares: Double,
    val cost: Double,
    val marketValue: Double,
    val posPnl: Double,
    val pnlPct: Double,
    val todayPnl: Double,
    val marketValueText: String,
    val todayPnlText: String,
    val pnlText: String,
    val pnlPctText: String,
    val alertDesc: String,
    val hasAlert: Boolean
)

private fun signed2(v: Double): String {
    val prefix = if (v >= 0) "+" else ""
    return prefix + fmt2(v)
}

internal fun ViewContainer<*, *>.watchlistNavBar(ctx: WatchlistPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(0xFF1976D2)
            paddingTop(ctx.pagerData.statusBarHeight)
            height(48f + ctx.pagerData.statusBarHeight)
        }
        View {
            attr { padding(left = 12f, top = 16f, right = 12f, bottom = 16f) }
            event {
                click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
            }
            Text {
                attr {
                    text("< 返回")
                    fontSize(16f)
                    color(0xFFFFFFFF)
                }
            }
        }
        Text {
            attr {
                text("自选股")
                fontSize(17f)
                fontWeightBold()
                color(0xFFFFFFFF)
                marginLeft(4f)
            }
        }
        vif({ ctx.rows.isNotEmpty() }) {
            Text {
                attr {
                    text(ctx.rows.size.toString() + " 只")
                    fontSize(12f)
                    color(0xFFB3D9FF)
                    marginLeft(6f)
                }
            }
        }
        View { attr { flex(1f) } }
        refreshButton({ ctx.isLoading }, foreground = 0xFFFFFFFF) { ctx.reload(showFeedback = true) }
    }
}

internal fun ViewContainer<*, *>.watchSummary(ctx: WatchlistPage) {
    View {
        attr {
            margin(top = 8f, left = 12f, right = 12f, bottom = 4f)
            padding(top = 10f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }
        Text {
            attr {
                text("持仓总览")
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
        Text {
            attr {
                text(ctx.summaryRoll.display)
                fontSize(12f)
                color(0xFF555555)
                marginTop(4f)
                lineHeight(17f)
            }
        }
        View {
            attr {
                marginTop(8f)
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                minHeight(36f)
                accessibility("打开盈亏日历")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event { click { ctx.openModule(AppRoutes.CALENDAR) } }
            Text { attr { text("盈亏日历"); fontSize(12f); color(0xFF1976D2); fontWeightBold() } }
            Text { attr { text("  ·  每日持仓快照，点开看贡献"); fontSize(11f); color(0xFF888888); flex(1f) } }
            Text { attr { text("›"); fontSize(18f); color(0xFF9EA7B2) } }
        }
    }
}

/**
 * 自选列表首屏骨架：卡片外边距、内边距、行高与 [watchlistRow] 对齐。
 *
 * 原来的实现是一行居中的「加载中...」，数据到达前后版式完全不同，
 * 页面会整块跳一下——列表越长越明显。
 */
internal fun ViewContainer<*, *>.watchlistLoadingView(ctx: WatchlistPage) {
    val sweep = ctx.skeletonPulse
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
        }

        repeat(WATCH_SKELETON_ROWS) {
            watchlistSkeletonRow(sweep)
        }
    }
}

/** 骨架行数：够铺满一屏即可。 */
private const val WATCH_SKELETON_ROWS = 6

private fun ViewContainer<*, *>.watchlistSkeletonRow(sweep: MountPulse) {
    View {
        attr {
            flexDirectionColumn()
            margin(top = 5f, left = 12f, right = 12f, bottom = 0f)
            padding(top = 10f, left = 12f, bottom = 8f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        // 名称 / 代码 / 最新价 / 涨跌幅
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            View {
                attr { flex(1f); flexDirectionColumn() }
                skeletonBlock(height = 15f, w = 84f, sweep = sweep)
                View { attr { height(5f) } }
                skeletonBlock(height = 11f, w = 52f, sweep = sweep)
            }
            skeletonBlock(height = 16f, w = 56f, sweep = sweep)
            View { attr { width(8f) } }
            skeletonBlock(height = 12f, w = 48f, sweep = sweep)
        }

        // 持仓/关注说明行
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(8f) }
            skeletonBlock(height = 11f, w = 96f, sweep = sweep)
            View { attr { flex(1f) } }
            skeletonBlock(height = 11f, w = 72f, sweep = sweep)
        }

        View {
            attr {
                height(1f)
                backgroundColor(0xFFF0F2F5)
                margin(top = 8f, bottom = 6f)
            }
        }

        // 操作行
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            skeletonBlock(height = 24f, w = 110f, radius = 14f, sweep = sweep)
            View { attr { flex(1f) } }
            skeletonBlock(height = 24f, w = 90f, radius = 14f, sweep = sweep)
        }
    }
}

/**
 * 空态。
 *
 * 原来只有标题 + 一句「在个股行情页标题栏点 ☆」——用户读完知道该怎么做了，
 * 但**当前屏幕上没有任何可点的东西**，只能自己退回行情页。这里直接给出出口。
 */
internal fun ViewContainer<*, *>.watchlistEmptyView(ctx: WatchlistPage) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 32f, right = 32f)
        }
        Text {
            attr {
                text("还没有自选股")
                fontSize(16f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
        Text {
            attr {
                text("加入自选后可以设置持仓成本、盯盘提醒，\n并在行情明细里看到持仓盈亏。")
                fontSize(13f)
                color(0xFF999999)
                marginTop(6f)
                textAlignCenter()
                lineHeight(19f)
            }
        }

        View {
            attr {
                marginTop(18f)
                padding(top = 11f, left = 26f, bottom = 11f, right = 26f)
                backgroundColor(0xFF1976D2)
                borderRadius(22f)
                pressedScale(ctx.press, WATCH_EMPTY_CTA_TAG, normal = 1f, pressed = 0.97f)
                accessibility("去行情页添加自选")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event {
                pressFeedback(ctx.press, WATCH_EMPTY_CTA_TAG)
                click {
                    ctx.press.releaseAll()
                    ctx.openModule(AppRoutes.MARKET)
                }
            }
            Text {
                attr {
                    text("去行情添加自选")
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFFFFFFFF)
                }
            }
        }

        Text {
            attr {
                text("在个股详情页标题栏点 ☆ 也可以加入")
                fontSize(11f)
                color(0xFFAAAAAA)
                marginTop(10f)
            }
        }
    }
}

private const val WATCH_EMPTY_CTA_TAG = "watchlist_empty_cta"
private const val WATCH_SAVE_TAG = "watchlist_dialog_save"


internal fun ViewContainer<*, *>.watchlistRow(ctx: WatchlistPage, row: WatchRowData) {
    View {
        attr {
            flexDirectionColumn()
            margin(top = 5f, left = 12f, right = 12f, bottom = 0f)
            padding(top = 10f, left = 12f, bottom = 8f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
            }
            event {
                click {
                    val params = JSONObject()
                    params.put("code", row.code)
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                        .openPage("stock_detail", params)
                }
            }
            View {
                attr { flex(1f) }
                Text {
                    attr {
                        text(row.name)
                        fontSize(15f)
                        fontWeightBold()
                        color(0xFF333333)
                    }
                }
                Text {
                    attr {
                        text(row.code)
                        fontSize(11f)
                        color(0xFF999999)
                        marginTop(1f)
                    }
                }
            }
            Text {
                attr {
                    text(row.priceText)
                    fontSize(16f)
                    fontWeightBold()
                    color(row.pctColor)
                    marginRight(8f)
                }
            }
            Text {
                attr {
                    text(row.pctText)
                    fontSize(12f)
                    color(row.pctColor)
                }
            }
        }

        if (row.shares > 0) {
            View {
                attr {
                    flexDirectionRow()
                    marginTop(6f)
                }
                Text {
                    attr {
                        text("持仓 " + trimHoldNum(row.shares) + " 股 @ " + fmt2(row.cost))
                        fontSize(11f)
                        color(0xFF888888)
                        flex(1f)
                    }
                }
                if (row.marketValueText.isNotEmpty()) {
                    Text {
                        attr {
                            text("市值 " + row.marketValueText)
                            fontSize(11f)
                            color(0xFF555555)
                            marginRight(10f)
                        }
                    }
                }
                Text {
                    attr {
                        text("盈亏 " + row.pnlText + (if (row.pnlPctText.isNotEmpty()) " (" + row.pnlPctText + ")" else ""))
                        fontSize(11f)
                        color(if (row.pnlText.startsWith("-")) StockColors.DOWN else StockColors.UP)
                    }
                }
            }
            View {
                attr {
                    flexDirectionRow()
                    marginTop(4f)
                }
                Text {
                    attr {
                        text("今日 " + row.todayPnlText)
                        fontSize(11f)
                        color(if (row.todayPnlText.startsWith("-")) StockColors.DOWN else StockColors.UP)
                        flex(1f)
                    }
                }
                if (row.hasAlert) {
                    Text {
                        attr {
                            text("提醒 " + row.alertDesc)
                            fontSize(11f)
                            color(0xFFA56100)
                        }
                    }
                }
            }
        } else {
            View {
                attr {
                    flexDirectionRow()
                    marginTop(6f)
                    alignItems(FlexAlign.CENTER)
                }
                Text {
                    attr {
                        text("仅关注 · 未设持仓")
                        fontSize(11f)
                        color(0xFFBBBBBB)
                        flex(1f)
                    }
                }
                if (row.hasAlert) {
                    Text {
                        attr {
                            text("提醒 " + row.alertDesc)
                            fontSize(11f)
                            color(0xFFA56100)
                            marginRight(10f)
                        }
                    }
                }
            }
        }

        View {
            attr {
                height(1f)
                backgroundColor(0xFFF0F2F5)
                margin(top = 8f, bottom = 6f)
            }
        }
        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
            }
            View {
                attr {
                    minHeight(44f)
                    padding(top = 5f, left = 10f, bottom = 5f, right = 10f)
                    backgroundColor(0xFFE3F2FD)
                    borderRadius(14f)
                    accessibility("设置${row.name}的持仓或提醒")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event { click { ctx.openEdit(row) } }
                Text {
                    attr {
                        text("✎ 设置持仓 / 提醒")
                        fontSize(12f)
                        color(0xFF1976D2)
                    }
                }
            }
            View { attr { flex(1f) } }
            View {
                attr {
                    minHeight(44f)
                    padding(left = 10f, top = 5f, right = 4f, bottom = 5f)
                    accessibility("将${row.name}移出自选及提醒")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event { click { ctx.removeItem(row.code) } }
                Text {
                    attr {
                        text("移出自选及提醒")
                        fontSize(12f)
                        color(StockColors.UP)
                    }
                }
            }
        }
    }
}

private fun trimHoldNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else fmt2(v)

internal fun ViewContainer<*, *>.watchEditDialog(ctx: WatchlistPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x88000000)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.dismissEdit() } }

        View {
            attr {
                overlayEnterExit(ctx.editOverlay)
            }

            Text {
                attr {
                    text("自选设置 · " + ctx.editName + " (" + ctx.editCode + ")")
                    fontSize(16f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }

            Text {
                attr {
                    text("持仓股数（0 表示仅关注不持仓）")
                    fontSize(12f)
                    color(0xFF666666)
                    marginTop(12f)
                }
            }
            Input {
                attr {
                    height(36f)
                    margin(top = 4f)
                    fontSize(14f)
                    color(Color(0xFF333333))
                    editable(true)
                    text(ctx.editSharesText)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(8f)
                }
                event {
                    textDidChange(isSyncEdit = true) { params -> ctx.editSharesText = params.text }
                }
            }

            Text {
                attr {
                    text("持仓成本价")
                    fontSize(12f)
                    color(0xFF666666)
                    marginTop(10f)
                }
            }
            Input {
                attr {
                    height(36f)
                    margin(top = 4f)
                    fontSize(14f)
                    color(Color(0xFF333333))
                    editable(true)
                    text(ctx.editCostText)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(8f)
                }
                event {
                    textDidChange(isSyncEdit = true) { params -> ctx.editCostText = params.text }
                }
            }

            Text { attr { text(ctx.editMessage); fontSize(12f); color(0xFFD32F2F); marginTop(6f) } }
            Scroller {
                attr { height((ctx.editRules.size * 44f).coerceAtMost(132f)); flexDirectionColumn(); scrollEnable(true) }
                vfor({ ctx.editRules }) { rule ->
                    View {
                        attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(6f) }
                        Text { attr { text(describeAlertType(rule.type) + " " + rule.threshold.toString()); fontSize(12f); flex(1f) } }
                        View {
                            attr { padding(8f) }
                            event { click { ctx.toggleRule(rule) } }
                            Text { attr { text(if (rule.enabled) "停用" else "启用"); fontSize(12f); color(0xFF1976D2) } }
                        }
                        View {
                            attr { padding(8f) }
                            event { click { ctx.deleteRule(rule) } }
                            Text { attr { text("删除此规则"); fontSize(12f); color(0xFFD32F2F) } }
                        }
                    }
                }
            }

            Text {
                attr {
                    text("新增提醒（数据刷新时检查，可能延迟）")
                    fontSize(12f)
                    color(0xFF666666)
                    marginTop(10f)
                }
            }
            View {
                attr {
                    flexDirectionRow()
                    flexWrapWrap()
                    marginTop(6f)
                }
                watchAlertChip(ctx, -1, "不新增")
                watchAlertChip(ctx, 0, "价格 ≥")
                watchAlertChip(ctx, 1, "价格 ≤")
                watchAlertChip(ctx, 2, "涨幅 ≥")
                watchAlertChip(ctx, 3, "跌幅 ≥")
            }

            vif({ ctx.editAlertType >= 0 }) {
                Text {
                    attr {
                        text(if (ctx.editAlertType == 2) "触发涨幅阈值（%）" else if (ctx.editAlertType == 3) "触发跌幅阈值（%）" else "触发价位")
                        fontSize(12f)
                        color(0xFF666666)
                        marginTop(10f)
                    }
                }
                Input {
                    attr {
                        height(36f)
                        margin(top = 4f)
                        fontSize(14f)
                        color(Color(0xFF333333))
                        editable(true)
                        text(ctx.editThresholdText)
                        backgroundColor(0xFFF5F5F5)
                        borderRadius(8f)
                    }
                    event {
                        textDidChange(isSyncEdit = true) { params -> ctx.editThresholdText = params.text }
                    }
                }
            }

            View {
                attr {
                    flexDirectionRow()
                    marginTop(14f)
                }
                View {
                    attr {
                        flex(1f)
                        height(44f)
                        backgroundColor(0xFFF5F5F5)
                        borderRadius(19f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                        accessibility("取消编辑")
                        accessibilityRole(AccessibilityRole.BUTTON)
                        accessibilityInfo(true, false)
                    }
                    event { click { ctx.dismissEdit() } }
                    Text {
                        attr {
                            text("取消")
                            fontSize(14f)
                            color(0xFF666666)
                        }
                    }
                }
                View {
                    attr {
                        flex(1f)
                        height(44f)
                        backgroundColor(0xFF1976D2)
                        borderRadius(19f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                        marginLeft(12f)
                        pressedScale(ctx.press, WATCH_SAVE_TAG, normal = 1f, pressed = 0.97f)
                        accessibility("保存持仓与提醒")
                        accessibilityRole(AccessibilityRole.BUTTON)
                        accessibilityInfo(true, false)
                    }
                    event {
                        pressFeedback(ctx.press, WATCH_SAVE_TAG)
                        click {
                            ctx.press.releaseAll()
                            ctx.saveEdit()
                        }
                    }
                    Text {
                        attr {
                            text("保存")
                            fontSize(14f)
                            fontWeightBold()
                            color(0xFFFFFFFF)
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.watchAlertChip(ctx: WatchlistPage, type: Int, label: String) {
    selectionChip(label, { ctx.editAlertType == type }) { ctx.editAlertType = type }
}
