package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
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
import com.kuikly.stock.data.PriceAlertRule
import com.kuikly.stock.data.WatchHolding
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.data.describeAlertType
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import com.tencent.kuikly.core.coroutines.launch

@Page("watchlist")
class WatchlistPage : Pager() {

    internal var rows: ObservableList<WatchRowData> by observableList()

    internal var isLoading by observable(false)

    internal var summaryLine by observable("")

    internal var showEdit by observable(false)
    internal var editCode by observable("")
    internal var editName by observable("")
    internal var editSharesText by observable("")
    internal var editCostText by observable("")
    internal var editAlertType by observable(-1)
    internal var editThresholdText by observable("")

    override fun didInit() {
        super.didInit()
        reload()
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
                vif({ ctx.rows.isNotEmpty() }) {
                    watchSummary(ctx)
                }
                Scroller {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        scrollEnable(true)
                    }
                    vif({ ctx.isLoading && ctx.rows.isEmpty() }) {
                        watchlistLoadingView()
                    }
                    vif({ !ctx.isLoading && ctx.rows.isEmpty() }) {
                        watchlistEmptyView()
                    }
                    vif({ ctx.rows.isNotEmpty() }) {
                        vfor({ ctx.rows }) { row ->
                            watchlistRow(ctx, row)
                        }
                        View { attr { height(10f) } }
                    }
                }
                vif({ ctx.showEdit }) {
                    watchEditDialog(ctx)
                }
            }
        }
    }

    internal fun reload() {
        if (isLoading) return
        isLoading = true
        rows.clear()
        lifecycleScope.launch {
            val watch = WatchStore.list()
            val built = mutableListOf<WatchRowData>()
            for (h in watch) {
                val row = buildRow(h)
                if (row != null) built.add(row)
            }
            rows.addAll(built)
            summaryLine = buildSummary(built)
            isLoading = false
        }
    }

    private suspend fun buildRow(h: WatchHolding): WatchRowData? {
        val d = try { StockRepository.loadStockDetail(h.code) } catch (e: Throwable) { null }
        val rt = d?.realtime
        val price = rt?.price
        val pct = rt?.changePercent ?: 0.0
        val change = rt?.change ?: 0.0
        val color = when {
            pct > 0 -> 0xFFE53935
            pct < 0 -> 0xFF43A047
            else -> 0xFF888888
        }
        val alert = WatchStore.alertOf(h.code)
        val alertDesc = if (alert != null && alert.enabled) {
            describeAlertType(alert.type) + " " + fmt2(alert.threshold)
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
            hasAlert = alert != null && alert.enabled
        )
    }

    private fun buildSummary(items: List<WatchRowData>): String {
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
        if (mv <= 0) return "未设置持仓，收藏的股票仅作关注。"
        val posPct = if (costSum > 0) pos / costSum * 100.0 else 0.0
        val sb = StringBuilder()
        sb.append("持仓市值 ").append(fmt2(mv))
        sb.append("   持仓盈亏 ").append(signed2(pos)).append(" (").append(signed2(posPct)).append("%)")
        sb.append("   今日盈亏 ").append(signed2(today))
        return sb.toString()
    }

    internal fun openEdit(row: WatchRowData) {
        editCode = row.code
        editName = row.name
        editSharesText = if (row.shares > 0) trimNum(row.shares) else ""
        editCostText = if (row.cost > 0) fmt2(row.cost) else ""
        val alert = WatchStore.alertOf(row.code)
        editAlertType = alert?.type ?: -1
        editThresholdText = alert?.let { fmt2(it.threshold) } ?: ""
        showEdit = true
    }

    internal fun saveEdit() {
        val shares = editSharesText.trim().toDoubleOrNull() ?: 0.0
        val cost = editCostText.trim().toDoubleOrNull() ?: 0.0
        if (shares < 0 || cost < 0) return
        WatchStore.updateHolding(WatchHolding(editCode, editName, shares, cost))
        val type = editAlertType
        if (type >= 0) {
            val threshold = editThresholdText.trim().toDoubleOrNull() ?: 0.0
            if (threshold > 0) {
                WatchStore.upsertAlert(PriceAlertRule(editCode, editName, type, threshold, true))
            } else {
                WatchStore.removeAlert(editCode)
            }
        } else {
            WatchStore.removeAlert(editCode)
        }
        showEdit = false
        reload()
    }

    internal fun removeItem(code: String) {
        WatchStore.remove(code)
        WatchStore.removeAlert(code)
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
        View {
            attr { padding(left = 10f, top = 12f, right = 14f, bottom = 12f) }
            event { click { ctx.reload() } }
            Text {
                attr {
                    text("刷新")
                    fontSize(13f)
                    color(0xFFFFFFFF)
                }
            }
        }
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
                text(ctx.summaryLine)
                fontSize(12f)
                color(0xFF555555)
                marginTop(4f)
                lineHeight(17f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.watchlistLoadingView() {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        Text {
            attr {
                text("加载中...")
                fontSize(14f)
                color(0xFF666666)
            }
        }
    }
}

internal fun ViewContainer<*, *>.watchlistEmptyView() {
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
                text("在个股行情页标题栏点 ☆ 即可加入自选；\n加入后可设置持仓成本与盯盘提醒。")
                fontSize(13f)
                color(0xFF999999)
                marginTop(6f)
                textAlignCenter()
                lineHeight(19f)
            }
        }
    }
}

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
                        color(if (row.pnlText.startsWith("-")) 0xFF43A047 else 0xFFE53935)
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
                        color(if (row.todayPnlText.startsWith("-")) 0xFF43A047 else 0xFFE53935)
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
                    padding(top = 5f, left = 10f, bottom = 5f, right = 10f)
                    backgroundColor(0xFFE3F2FD)
                    borderRadius(14f)
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
                    padding(left = 10f, top = 5f, right = 4f, bottom = 5f)
                }
                event { click { ctx.removeItem(row.code) } }
                Text {
                    attr {
                        text("移出自选")
                        fontSize(12f)
                        color(0xFFE53935)
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
        event { click { ctx.showEdit = false } }

        View {
            attr {
                width(ctx.pagerData.pageViewWidth - 40f)
                flexDirectionColumn()
                backgroundColor(0xFFFFFFFF)
                borderRadius(12f)
                padding(left = 16f, top = 16f, right = 16f, bottom = 16f)
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

            Text {
                attr {
                    text("盯盘提醒（数据刷新时检查，可能延迟）")
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
                watchAlertChip(ctx, -1, "关闭")
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
                        height(38f)
                        backgroundColor(0xFFF5F5F5)
                        borderRadius(19f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                    }
                    event { click { ctx.showEdit = false } }
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
                        height(38f)
                        backgroundColor(0xFF1976D2)
                        borderRadius(19f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                        marginLeft(12f)
                    }
                    event { click { ctx.saveEdit() } }
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
    val selected = ctx.editAlertType == type
    View {
        attr {
            marginRight(6f)
            marginBottom(6f)
            padding(left = 10f, top = 4f, right = 10f, bottom = 4f)
            backgroundColor(if (selected) 0xFF1976D2 else 0xFFE3F2FD)
            borderRadius(13f)
        }
        event {
            click { ctx.editAlertType = type }
        }
        Text {
            attr {
                text(label)
                fontSize(12f)
                fontWeightBold()
                color(if (selected) 0xFFFFFFFF else 0xFF1976D2)
            }
        }
    }
}
