package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ui.component.MountPulse
import com.kuikly.stock.ui.component.NumberRoll
import com.kuikly.stock.ui.component.Overlay
import com.kuikly.stock.ui.component.overlayEnterExit
import com.kuikly.stock.ui.component.PressState
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedScale
import com.kuikly.stock.ui.component.ensureSkeletonVisible
import com.kuikly.stock.ui.component.skeletonBlock
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
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.REFRESH_SPIN_STEP_MS
import com.kuikly.stock.ui.component.appBottomNav
import com.kuikly.stock.ui.component.pullRefreshLabel
import com.kuikly.stock.ui.component.pullToRefresh
import com.kuikly.stock.ui.component.refreshButton
import com.kuikly.stock.ui.component.segmentedControl
import com.kuikly.stock.ui.component.dialogActions
import com.kuikly.stock.ui.component.dialogField
import com.kuikly.stock.ui.component.statusFeedback
import com.kuikly.stock.ui.component.openModule
import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.kuikly.stock.ui.theme.AppRadius
import com.kuikly.stock.ui.theme.AppSize
import com.kuikly.stock.ui.theme.AppSpace

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
                    backgroundColor(AppColor.SURFACE_SOFT)
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
            val startedAt = System.currentTimeMillis()
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
                if (showFeedback) saveMessage = "自选仓已刷新，共 ${built.size} 只"
            } catch (e: Throwable) {
                saveMessage = "刷新失败，请重试"
            } finally {
                // 骨架屏保证最短展示：本地数据秒载时也不让骨架"一闪而过"
                ensureSkeletonVisible(startedAt)
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
            else -> AppColor.TEXT_HINT_SOFT
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
        // 仅关注（未设持仓）时不再灌 "0" / "0.00" 占位——那是用户要先删掉才能填的「默认底字」。
        // 留空即可，输入框自己的 placeholder 会给提示；保存时 saveEdit 把空当作「仅关注」处理。
        editSharesText = if (row.shares > 0.0) trimNum(row.shares) else ""
        editCostText = if (row.cost > 0.0) fmt2(row.cost) else ""
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
        // 空输入 = 仅关注（不持仓）。HoldingInput.parse 只认数字，所以把空归一成 "0" 走 clear 分支，
        // 用户就不必为了「只关注不持仓」而手动敲 0 / 0.00。
        val sharesForParse = editSharesText.trim().ifEmpty { "0" }
        val costForParse = editCostText.trim().ifEmpty { "0" }
        val input = HoldingInput.parse(editCode, sharesForParse, costForParse, allowClear = true)
        if (input == null) {
            editMessage = "股数与成本价需为有效正数；都留空表示仅关注不持仓"
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
        saveMessage = if (alertSaved) "已保存自选仓与提醒" else "提醒保存失败，请重试"
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
            backgroundColor(AppColor.PRIMARY_SOFT)
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
                    color(AppColor.ON_DARK)
                }
            }
        }
        Text {
            attr {
                text("自选仓")
                fontSize(17f)
                fontWeightBold()
                color(AppColor.ON_DARK)
                marginLeft(4f)
            }
        }
        vif({ ctx.rows.isNotEmpty() }) {
            Text {
                attr {
                    text(ctx.rows.size.toString() + " 只")
                    fontSize(12f)
                    color(AppColor.ON_DARK_ACCENT_STRONG)
                    marginLeft(6f)
                }
            }
        }
        View { attr { flex(1f) } }
        refreshButton({ ctx.isLoading }, foreground = AppColor.ON_DARK) { ctx.reload(showFeedback = true) }
    }
}

internal fun ViewContainer<*, *>.watchSummary(ctx: WatchlistPage) {
    View {
        attr {
            margin(top = 8f, left = 12f, right = 12f, bottom = 4f)
            padding(top = 10f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }
        Text {
            attr {
                text("持仓总览")
                fontSize(13f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
            }
        }
        Text {
            attr {
                text(ctx.summaryRoll.display)
                fontSize(12f)
                color(AppColor.TEXT_GRAY)
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
            Text { attr { text("盈亏日历"); fontSize(12f); color(AppColor.PRIMARY_SOFT); fontWeightBold() } }
            Text { attr { text("  ·  每日持仓快照，点开看贡献"); fontSize(11f); color(AppColor.TEXT_HINT_SOFT); flex(1f) } }
            Text { attr { text("›"); fontSize(18f); color(AppColor.TEXT_MUTED) } }
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
            backgroundColor(AppColor.SURFACE)
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
                backgroundColor(AppColor.BG_SOFT)
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
        View {
            attr {
                marginTop(24f)
                marginBottom(24f)
                padding(20f)
                borderRadius(AppRadius.LG)
                backgroundColor(AppColor.SURFACE)
                alignItems(FlexAlign.CENTER)
            }
            Text {
                attr {
                    text("还没有自选仓")
                    fontSize(16f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                }
            }
            Text {
                attr {
                    text("加入自选仓后可以设置持仓成本、盯盘提醒，\n并在行情明细里看到持仓盈亏。")
                    fontSize(13f)
                    color(AppColor.TEXT_SUB)
                    marginTop(6f)
                    textAlignCenter()
                    lineHeight(19f)
                }
            }
            View {
                attr {
                    marginTop(18f)
                    padding(top = 11f, left = 26f, bottom = 11f, right = 26f)
                    backgroundColor(AppColor.PRIMARY_SOFT)
                    borderRadius(22f)
                    pressedScale(ctx.press, WATCH_EMPTY_CTA_TAG, normal = 1f, pressed = 0.97f)
                    accessibility("去行情页添加自选仓")
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
                        text("去行情添加自选仓")
                        fontSize(14f)
                        fontWeightBold()
                        color(AppColor.ON_DARK)
                    }
                }
            }
            Text {
                attr {
                    text("在个股详情页标题栏点 ☆ 也可以加入")
                    fontSize(11f)
                    color(AppColor.TEXT_MUTED)
                    marginTop(10f)
                }
            }
        }
    }
}

private const val WATCH_EMPTY_CTA_TAG = "watchlist_empty_cta"
private const val WATCH_SAVE_TAG = "watchlist_dialog_save"
private const val WATCH_CANCEL_TAG = "watchlist_dialog_cancel"

/**
 * 提醒类型的五个选项。
 *
 * 顺序即下标：`editAlertType` 用 -1 表示「不新增」，所以选中的下标是 `type + 1`。
 * -1 是一个真实取值而不是「未选」，不要把它也塞进列表。
 */
private val ALERT_TYPE_OPTIONS = listOf("不新增", "价格≥", "价格≤", "涨幅≥", "跌幅≥")


internal fun ViewContainer<*, *>.watchlistRow(ctx: WatchlistPage, row: WatchRowData) {
    View {
        attr {
            flexDirectionColumn()
            margin(top = 5f, left = 12f, right = 12f, bottom = 0f)
            padding(top = 10f, left = 12f, bottom = 8f, right = 12f)
            backgroundColor(AppColor.SURFACE)
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
                        color(AppColor.TEXT_INK)
                    }
                }
                Text {
                    attr {
                        text(row.code)
                        fontSize(11f)
                        color(AppColor.TEXT_HINT)
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
                        color(AppColor.TEXT_HINT_SOFT)
                        flex(1f)
                    }
                }
                if (row.marketValueText.isNotEmpty()) {
                    Text {
                        attr {
                            text("市值 " + row.marketValueText)
                            fontSize(11f)
                            color(AppColor.TEXT_GRAY)
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
                            color(AppColor.WARNING_TEXT)
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
                        color(AppColor.DISABLED)
                        flex(1f)
                    }
                }
                if (row.hasAlert) {
                    Text {
                        attr {
                            text("提醒 " + row.alertDesc)
                            fontSize(11f)
                            color(AppColor.WARNING_TEXT)
                            marginRight(10f)
                        }
                    }
                }
            }
        }

        View {
            attr {
                height(1f)
                backgroundColor(AppColor.BG_SOFT)
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
                    backgroundColor(AppColor.PRIMARY_BG)
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
                        color(AppColor.PRIMARY_SOFT)
                    }
                }
            }
            View { attr { flex(1f) } }
            View {
                attr {
                    minHeight(44f)
                    padding(left = 10f, top = 5f, right = 4f, bottom = 5f)
                    accessibility("将${row.name}移出自选仓及提醒")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event { click { ctx.removeItem(row.code) } }
                Text {
                    attr {
                        text("移出自选仓及提醒")
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
            backgroundColor(AppColor.SCRIM)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 20f, right = 20f)
        }
        event { click { ctx.dismissEdit() } }

        View {
            attr {
                overlayEnterExit(ctx.editOverlay)
                // 定宽居中白卡片：内容不能直接铺在半透明遮罩上、顶到屏幕两边。
                width((ctx.pagerData.pageViewWidth - 48f).coerceIn(280f, 360f))
                backgroundColor(AppColor.SURFACE)
                borderRadius(AppRadius.LG)
                padding(AppSpace.AIRY)
            }
            // 消费点击，阻止冒泡到遮罩——否则点输入框/卡片空白处都会触发 dismiss，弹窗一点就关。
            event { click { } }

            Text {
                attr {
                    text("自选仓设置 · " + ctx.editName + " (" + ctx.editCode + ")")
                    fontSize(AppFont.TITLE)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                }
            }

            dialogField(
                label = "持仓股数",
                value = { ctx.editSharesText },
                placeholder = "留空或 0 表示仅关注",
                onTextChange = { ctx.editSharesText = it },
            )
            dialogField(
                label = "持仓成本价",
                value = { ctx.editCostText },
                placeholder = "每股成本，如 3.20",
                onTextChange = { ctx.editCostText = it },
            )

            Text {
                attr {
                    text(ctx.editMessage)
                    fontSize(AppFont.NOTE)
                    color(AppColor.DANGER)
                    marginTop(AppSpace.TIGHT)
                }
            }
            Scroller {
                attr {
                    height((ctx.editRules.size * AppSize.TOUCH_MIN).coerceAtMost(132f))
                    flexDirectionColumn()
                    scrollEnable(true)
                }
                vfor({ ctx.editRules }) { rule ->
                    View {
                        attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(AppSpace.TIGHT) }
                        Text {
                            attr {
                                text(describeAlertType(rule.type) + " " + rule.threshold.toString())
                                fontSize(AppFont.NOTE)
                                flex(1f)
                            }
                        }
                        View {
                            attr { padding(AppSpace.TIGHT) }
                            event { click { ctx.toggleRule(rule) } }
                            Text {
                                attr {
                                    text(if (rule.enabled) "停用" else "启用")
                                    fontSize(AppFont.NOTE)
                                    color(AppColor.PRIMARY_SOFT)
                                }
                            }
                        }
                        View {
                            attr { padding(AppSpace.TIGHT) }
                            event { click { ctx.deleteRule(rule) } }
                            Text { attr { text("删除此规则"); fontSize(AppFont.NOTE); color(AppColor.DANGER) } }
                        }
                    }
                }
            }

            Text {
                attr {
                    text("新增提醒（数据刷新时检查，可能延迟）")
                    fontSize(AppFont.NOTE)
                    color(AppColor.TEXT_GRAY)
                    marginTop(AppSpace.GAP)
                }
            }
            segmentedControl(
                options = ALERT_TYPE_OPTIONS,
                selectedIndex = { ctx.editAlertType + 1 },
                itemWidth = 56f,
                fontSize = AppFont.CAPTION,
                marginTop = AppSpace.TIGHT,
                selfAlign = FlexAlign.FLEX_START,
                onSelect = { ctx.editAlertType = it - 1 },
            )

            vif({ ctx.editAlertType >= 0 }) {
                dialogField(
                    label = if (ctx.editAlertType == 2) {
                        "触发涨幅阈值（%）"
                    } else if (ctx.editAlertType == 3) {
                        "触发跌幅阈值（%）"
                    } else {
                        "触发价位"
                    },
                    value = { ctx.editThresholdText },
                    placeholder = if (ctx.editAlertType == 2 || ctx.editAlertType == 3) "如 5 表示 5%" else "如 3.50",
                    onTextChange = { ctx.editThresholdText = it },
                )
            }

            dialogActions(
                confirmLabel = "保存",
                confirmAccessibilityText = "保存持仓与提醒",
                cancelAccessibilityText = "取消编辑",
                press = ctx.press,
                cancelTag = WATCH_CANCEL_TAG,
                confirmTag = WATCH_SAVE_TAG,
                onCancel = { ctx.dismissEdit() },
                onConfirm = { ctx.saveEdit() },
            )
        }
    }
}
