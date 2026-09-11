package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager

import com.kuikly.stock.data.AlertEngine
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.data.HoldingInput
import com.kuikly.stock.data.WatchHolding
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.risk.HoldingRiskLine
import com.kuikly.stock.risk.HoldingSnapshot
import com.kuikly.stock.risk.PortfolioRiskCalculator
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

@Page(AppRoutes.RISK)
class RiskCenterPage : BasePager() {
    internal var refreshing by observable(false)
    internal var refreshMessage by observable("")
    internal var refreshIsError by observable(false)
    internal var marketValue by observable("--")
    internal var pnl by observable("--")
    internal var pnlColor by observable(0xFF627083L)
    internal var stockWeight by observable("--")
    internal var industryWeight by observable("--")
    internal var emptyState by observable(false)
    internal var unavailable by observable(0)
    internal var riskMessages: ObservableList<String> by observableList()
    internal var lines: ObservableList<HoldingRiskLine> by observableList()
    internal var alertMessages: ObservableList<String> by observableList()

    internal var showAddDialog by observable(false)
    internal var addCodeText by observable("")
    internal var addSharesText by observable("")
    internal var addCostText by observable("")
    internal var addMessage by observable("")

    internal var showEditDialog by observable(false)
    internal var editMessage by observable("")
    internal var editCode by observable("")
    internal var editName by observable("")
    internal var editSharesText by observable("")
    internal var editCostText by observable("")

    internal var simulateDropPercent by observable(10.0)
    internal var simulateResultText by observable("")
    internal var expandedRiskKey by observable("")
    internal var showWhatIf by observable(false)

    override fun didInit() {
        super.didInit()
        reload()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        reload()
    }

    internal fun refreshPage() {
        if (refreshing) return
        refreshing = true
        refreshMessage = ""
        lifecycleScope.launch {
            try {
                delay(0)
                reload()
            } catch (e: Throwable) {
                refreshIsError = true
                refreshMessage = "刷新失败，请重试"
            } finally {
                refreshing = false
            }
        }
    }

    internal fun reload() {
        try {
            val snapshots = WatchStore.list().map { holding ->
                val detail = runCatching { StockDb.stockDetail(holding.code) }.getOrNull()
                HoldingSnapshot(
                    code = holding.code,
                    name = holding.name.ifBlank { detail?.info?.name ?: holding.code },
                    industry = detail?.info?.industry ?: "未分类",
                    shares = holding.shares,
                    cost = holding.cost,
                    currentPrice = detail?.realtime?.price,
                )
            }.filter { it.shares > 0.0 }
            val result = PortfolioRiskCalculator.calculate(snapshots)
            emptyState = snapshots.isEmpty()
            unavailable = result.unavailableCount
            marketValue = if (result.pricedCount > 0) "¥ ${fmt2(result.marketValue)}" else "--"
            pnl = if (result.pricedCount > 0) signedMoney(result.pnl) + "  " + signedPercent(result.pnlRate) else "--"
            pnlColor = if (result.pnl > 0.0) 0xFFD84343 else if (result.pnl < 0.0) 0xFF188B57 else 0xFF627083
            stockWeight = if (result.pricedCount > 0) percent(result.maxStockWeight) else "--"
            industryWeight = if (result.pricedCount > 0) percent(result.maxIndustryWeight) else "--"
            lines.clear(); lines.addAll(result.lines)
            riskMessages.clear(); riskMessages.addAll(result.riskMessages)
            alertMessages.clear(); alertMessages.addAll(runCatching { AlertEngine.hits().map { it.second } }.getOrDefault(emptyList()))
            refreshIsError = false
            if (refreshMessage.isEmpty()) refreshMessage = "组合风险已重新计算"
            updateSimulate()
        } catch (e: Throwable) {
            refreshIsError = true
            refreshMessage = "刷新失败，请重试"
        }
    }

    internal fun openAddDialog() {
        addCodeText = ""
        addSharesText = ""
        addCostText = ""
        addMessage = ""
        showAddDialog = true
    }

    internal fun confirmAdd() {
        val input = HoldingInput.parse(addCodeText, addSharesText, addCostText)
        if (input == null) {
            addMessage = "请输入6位数字股票代码、有效正数股数和成本价"
            return
        }
        val (code, shares, cost) = input
        val detail = runCatching { StockDb.stockDetail(code) }.getOrNull()
        val name = detail?.info?.name ?: code
        WatchStore.updateHolding(WatchHolding(code, name, shares, cost))
        showAddDialog = false
        addMessage = ""
        refreshMessage = "已添加 $name 持仓"
        reload()
    }

    internal fun openEditDialog(line: HoldingRiskLine) {
        editMessage = ""
        editCode = line.code
        editName = line.name
        editSharesText = trimNum(line.shares)
        editCostText = fmt2(line.costValue / line.shares)
        showEditDialog = true
    }

    internal fun confirmEdit() {
        val input = HoldingInput.parse(editCode, editSharesText, editCostText, allowClear = true)
        if (input == null) {
            editMessage = "请输入有效股数和成本价；股数填0清空持仓并保留自选"
            return
        }
        WatchStore.updateHolding(WatchHolding(input.code, editName, input.shares, input.cost))
        showEditDialog = false
        reload()
    }

    internal fun removeHolding(code: String) {
        WatchStore.clearHolding(code)
        refreshMessage = "已清空持仓，保留自选和提醒"
        reload()
    }

    internal fun openDetail(code: String) {
        val params = JSONObject()
        params.put("code", code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("stock_detail", params)
    }

    internal fun toggleRiskDetail(key: String) {
        expandedRiskKey = if (expandedRiskKey == key) "" else key
    }

    internal fun updateSimulate() {
        try {
            val drop = simulateDropPercent / 100.0
            var totalMV = 0.0
            var totalLoss = 0.0
            for (line in lines) {
                totalMV += line.marketValue
                totalLoss += line.marketValue * drop
            }
            val newMV = totalMV * (1 - drop)
            simulateResultText = "若市场下跌 ${fmt2(simulateDropPercent)}%，组合市值将从 ¥${fmt2(totalMV)} 跌至 ¥${fmt2(newMV)}，浮亏 ¥${fmt2(totalLoss)}"
        } catch (e: Throwable) {
            simulateResultText = ""
        }
    }

    internal fun changeSimulate(delta: Double) {
        simulateDropPercent = (simulateDropPercent + delta).coerceIn(1.0, 50.0)
        updateSimulate()
    }

    internal fun toggleWhatIf() {
        showWhatIf = !showWhatIf
        if (showWhatIf) updateSimulate()
    }

    private fun trimNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else fmt2(v)

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); flexDirectionColumn(); backgroundColor(0xFFF4F7FB) }
                pageTitleBar(ctx, "组合风险", "基于本地持仓与行情计算", { ctx.refreshing }) { ctx.refreshPage() }
                statusFeedback({ ctx.refreshMessage }, { ctx.refreshIsError })
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true); padding(16f) }
                    riskOverview(ctx)
                    vif({ ctx.showWhatIf }) {
                        riskWhatIfCard(ctx)
                    }
                    vif({ ctx.emptyState }) { riskEmpty() }
                    vif({ !ctx.emptyState }) {
                        riskSectionTitle("风险提示 · 点击展开建议")
                        vif({ ctx.riskMessages.isEmpty() }) { calmRiskState() }
                        vfor({ ctx.riskMessages }) { message ->
                            // 需要index，用hash替代
                            riskMessage(ctx, message, message.hashCode().toString())
                        }
                        riskSectionTitle("持仓拆解 · 点击编辑")
                        vfor({ ctx.lines }) { line -> holdingRiskRow(ctx, line) }
                        View {
                            attr {
                                flexDirectionRow()
                                marginTop(12f)
                                padding(12f)
                                backgroundColor(0xFFFFFFFF)
                                borderRadius(12f)
                                allCenter()
                            }
                            event { click { ctx.openAddDialog() } }
                            Text { attr { text("＋ 添加持仓 / 调整仓位"); fontSize(13f); fontWeightBold(); color(0xFF0B2B50) } }
                        }
                    }
                    velse {
                        View {
                            attr {
                                flexDirectionRow()
                                marginTop(12f)
                                padding(12f)
                                backgroundColor(0xFF0B2B50)
                                borderRadius(12f)
                                allCenter()
                            }
                            event { click { ctx.openAddDialog() } }
                            Text { attr { text("＋ 添加第一笔持仓"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } }
                        }
                    }
                    riskSectionTitle("已触发提醒")
                    vif({ ctx.alertMessages.isEmpty() }) { quietAlertState() }
                    vfor({ ctx.alertMessages }) { message ->
                        View {
                            attr { padding(13f); marginBottom(8f); borderRadius(12f); backgroundColor(0xFFFFF3E8) }
                            Text { attr { text(message); fontSize(13f); lineHeight(19f); color(0xFF8B4C12) } }
                        }
                    }
                    View { attr { height(18f) } }
                }
                vif({ ctx.showAddDialog }) { riskAddDialog(ctx) }
                vif({ ctx.showEditDialog }) { riskEditDialog(ctx) }
            }
        }
    }
}

private fun ViewContainer<*, *>.riskOverview(ctx: RiskCenterPage) {
    View {
        attr { padding(18f); borderRadius(18f); backgroundColor(0xFF0B2B50) }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            View {
                attr { flex(1f) }
                Text { attr { text("组合市值"); fontSize(12f); color(0xFF9EC8F5) } }
                Text { attr { text(ctx.marketValue); fontSize(27f); fontWeightBold(); color(Color.WHITE); marginTop(6f) } }
                Text { attr { text("累计盈亏  ${ctx.pnl}"); fontSize(13f); color(ctx.pnlColor); marginTop(7f) } }
            }
            View {
                attr {
                    padding(8f, 12f, 8f, 12f)
                    backgroundColor(0xFF123A68)
                    borderRadius(12f)
                }
                event { click { ctx.openAddDialog() } }
                Text { attr { text("＋ 添加持仓"); fontSize(12f); fontWeightBold(); color(0xFF9EC8F5) } }
            }
        }
        View { attr { flexDirectionRow(); marginTop(18f) } }
        View {
            attr { flex(1f) }
            Text { attr { text("最高单股占比"); fontSize(11f); color(0xFF9EB2C7) } }
            Text { attr { text(ctx.stockWeight); fontSize(17f); fontWeightBold(); color(Color.WHITE); marginTop(4f) } }
        }
        View {
            attr { flex(1f) }
            Text { attr { text("最高行业占比"); fontSize(11f); color(0xFF9EB2C7) } }
            Text { attr { text(ctx.industryWeight); fontSize(17f); fontWeightBold(); color(Color.WHITE); marginTop(4f) } }
        }
        vif({ ctx.unavailable > 0 }) {
            Text { attr { text("${ctx.unavailable} 个持仓缺少有效行情，未计入总值"); fontSize(11f); color(0xFFFFC77D); marginTop(12f) } }
        }
        View {
            attr { flexDirectionRow(); marginTop(14f) }
            View {
                attr {
                    padding(6f, 12f, 6f, 12f)
                    backgroundColor(0xFF1A4A7A)
                    borderRadius(10f)
                    marginRight(8f)
                }
                event { click { ctx.toggleWhatIf() } }
                Text {
                    attr {
                        text(if (ctx.showWhatIf) "收起压力测试" else "压力测试")
                        fontSize(11f)
                        color(0xFF9EC8F5)
                        fontWeightBold()
                    }
                }
            }
            View {
                attr {
                    padding(6f, 12f, 6f, 12f)
                    backgroundColor(0xFF1A4A7A)
                    borderRadius(10f)
                }
                event { click { ctx.refreshPage() } }
                Text { attr { text("重新计算"); fontSize(11f); color(0xFF9EC8F5) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.riskSectionTitle(title: String) {
    Text { attr { text(title); fontSize(17f); fontWeightBold(); color(0xFF172A43); margin(top = 20f, bottom = 10f) } }
}

private fun ViewContainer<*, *>.riskEmpty() {
    View {
        attr { padding(24f); marginTop(14f); borderRadius(15f); backgroundColor(Color.WHITE); alignItems(FlexAlign.CENTER) }
        Text { attr { text("还没有持仓数据"); fontSize(16f); fontWeightBold(); color(0xFF24364D) } }
        Text { attr { text("点击“添加持仓”或在自选页设置持仓股数与成本，风险中心会实时计算集中度与回撤，并支持压力测试。"); fontSize(12f); lineHeight(19f); color(0xFF7F8998); marginTop(7f); textAlignCenter() } }
    }
}

private fun ViewContainer<*, *>.calmRiskState() {
    View { attr { padding(14f); borderRadius(12f); backgroundColor(0xFFEAF8F0) }; Text { attr { text("暂未命中高集中度或高回撤规则，组合相对分散"); fontSize(13f); color(0xFF18794E) } } }
}

private fun ViewContainer<*, *>.quietAlertState() {
    View { attr { padding(14f); borderRadius(12f); backgroundColor(Color.WHITE) }; Text { attr { text("暂无触发中的价格提醒，去详情页或自选页设置提醒"); fontSize(13f); color(0xFF788494) } } }
}

private fun ViewContainer<*, *>.riskMessage(ctx: RiskCenterPage, message: String, key: String) {
    View {
        attr {
            padding(13f)
            marginBottom(8f)
            borderRadius(12f)
            backgroundColor(0xFFFFF3E8)
        }
        event { click { ctx.toggleRiskDetail(key) } }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            Text { attr { text(message); fontSize(13f); lineHeight(19f); color(0xFF8B4C12); flex(1f) } }
            Text { attr { text(if (ctx.expandedRiskKey == key) "⌃" else "⌄"); fontSize(14f); color(0xFF8B4C12); marginLeft(8f) } }
        }
        vif({ ctx.expandedRiskKey == key }) {
            View {
                attr { marginTop(8f); padding(10f); backgroundColor(0xFFFFFFFF); borderRadius(8f) }
                Text {
                    attr {
                        text(
                            when {
                                message.contains("单股集中度") -> "建议：单只股票仓位不超过50%，可通过减仓或增加其他持仓来分散风险。点击下方持仓可快速调整。"
                                message.contains("行业集中度") -> "建议：单一行业占比过高时，行业黑天鹅会影响组合。考虑配置不同行业资产。"
                                message.contains("回撤") -> "建议：回撤超15%需复核止损纪律，检查是否跌破关键支撑位，结合AI分析判断是否止损。"
                                else -> "风险提示仅基于本地持仓与行情计算，不构成投资建议。"
                            }
                        )
                        fontSize(11f)
                        color(0xFF8B4C12)
                        lineHeight(16f)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.holdingRiskRow(ctx: RiskCenterPage, line: HoldingRiskLine) {
    View {
        attr { padding(14f); marginBottom(9f); borderRadius(14f); backgroundColor(Color.WHITE) }
        View { attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            View {
                attr { flex(1f) }
                event { click { ctx.openDetail(line.code) } }
                Text { attr { text(line.name); fontSize(15f); fontWeightBold(); color(0xFF1C3048) } }
                Text { attr { text("${line.code} · ${line.industry} · ${trimHoldNum(line.shares)}股"); fontSize(11f); color(0xFF8993A1); marginTop(2f) } }
            }
            View {
                attr { flexDirectionColumn(); alignItems(FlexAlign.FLEX_END) }
                Text { attr { text(percent(line.weight)); fontSize(15f); fontWeightBold(); color(if (line.weight >= 0.5) 0xFFD35454 else 0xFF315C87) } }
                Text { attr { text("占比"); fontSize(10f); color(0xFF8993A1) } }
            }
        }
        View { attr { height(1f); backgroundColor(0xFFF0F2F5); margin(top = 10f, bottom = 10f) } }
        View {
            attr { flexDirectionRow() }
            View {
                attr { flex(1f) }
                Text { attr { text("市值 ¥${fmt2(line.marketValue)}"); fontSize(12f); color(0xFF1C3048); fontWeightBold() } }
                Text { attr { text("成本 ¥${fmt2(line.costValue)}"); fontSize(11f); color(0xFF8993A1); marginTop(2f) } }
            }
            View {
                attr { flex(1f); alignItems(FlexAlign.FLEX_END) }
                Text { attr { text("${signedMoney(line.pnl)} (${signedPercent(line.pnlRate)})"); fontSize(12f); color(if (line.pnl >= 0) 0xFFD84343 else 0xFF188B57); fontWeightBold() } }
                Text { attr { text("盈亏"); fontSize(11f); color(0xFF8993A1); marginTop(2f) } }
            }
        }
        View {
            attr { flexDirectionRow(); marginTop(12f) }
            View {
                attr {
                    padding(6f, 12f, 6f, 12f)
                    backgroundColor(0xFFF0F2F5)
                    borderRadius(10f)
                    marginRight(8f)
                }
                event { click { ctx.openEditDialog(line) } }
                Text { attr { text("✎ 调整"); fontSize(11f); color(0xFF315C87) } }
            }
            View {
                attr {
                    padding(6f, 12f, 6f, 12f)
                    backgroundColor(0xFFFFF0F0)
                    borderRadius(10f)
                    marginRight(8f)
                }
                event { click { ctx.removeHolding(line.code) } }
                Text { attr { text("清仓留自选"); fontSize(11f); color(0xFFD32F2F) } }
            }
            View { attr { flex(1f) } }
            View {
                attr {
                    padding(6f, 12f, 6f, 12f)
                    backgroundColor(0xFFE3F2FD)
                    borderRadius(10f)
                }
                event { click { ctx.openDetail(line.code) } }
                Text { attr { text("详情 ›"); fontSize(11f); color(0xFF1976D2); fontWeightBold() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.riskWhatIfCard(ctx: RiskCenterPage) {
    View {
        attr {
            padding(16f)
            borderRadius(14f)
            backgroundColor(Color.WHITE)
            marginTop(12f)
        }
        Text { attr { text("压力测试 · 模拟市场下跌"); fontSize(14f); fontWeightBold(); color(0xFF172A43) } }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(12f) }
            View {
                attr {
                    width(32f)
                    height(32f)
                    backgroundColor(0xFFF0F2F5)
                    borderRadius(8f)
                    allCenter()
                    marginRight(8f)
                }
                event { click { ctx.changeSimulate(-1.0) } }
                Text { attr { text("－"); fontSize(16f); color(0xFF315C87) } }
            }
            View {
                attr { flex(1f); alignItems(FlexAlign.CENTER) }
                Text { attr { text("${fmt2(ctx.simulateDropPercent)}%"); fontSize(20f); fontWeightBold(); color(0xFF0B2B50) } }
                Text { attr { text("模拟跌幅"); fontSize(11f); color(0xFF8993A1); marginTop(2f) } }
            }
            View {
                attr {
                    width(32f)
                    height(32f)
                    backgroundColor(0xFFF0F2F5)
                    borderRadius(8f)
                    allCenter()
                    marginLeft(8f)
                }
                event { click { ctx.changeSimulate(1.0) } }
                Text { attr { text("＋"); fontSize(16f); color(0xFF315C87) } }
            }
        }
        Text {
            attr {
                text(ctx.simulateResultText)
                fontSize(12f)
                color(0xFF5A6B82)
                marginTop(12f)
                lineHeight(18f)
            }
        }
        View {
            attr { flexDirectionRow(); marginTop(12f) }
            riskQuickChip(ctx, 5.0, "跌5%")
            riskQuickChip(ctx, 10.0, "跌10%")
            riskQuickChip(ctx, 20.0, "跌20%")
            riskQuickChip(ctx, 30.0, "跌30%")
        }
        Text {
            attr {
                text("拖动上方按钮调整跌幅，实时查看组合回撤。压力测试帮助你提前规划止损与仓位。")
                fontSize(10f)
                color(0xFF999999)
                marginTop(8f)
                lineHeight(14f)
            }
        }
    }
}

private fun ViewContainer<*, *>.riskQuickChip(ctx: RiskCenterPage, value: Double, label: String) {
    View {
        attr {
            padding(6f, 10f, 6f, 10f)
            backgroundColor(if (ctx.simulateDropPercent == value) 0xFF0B2B50 else 0xFFF0F2F5)
            borderRadius(10f)
            marginRight(6f)
        }
        event {
            click {
                ctx.simulateDropPercent = value
                ctx.updateSimulate()
            }
        }
        Text {
            attr {
                text(label)
                fontSize(11f)
                color(if (ctx.simulateDropPercent == value) 0xFFFFFFFF else 0xFF5A6B82)
                fontWeightBold()
            }
        }
    }
}

private fun ViewContainer<*, *>.riskAddDialog(ctx: RiskCenterPage) {
    View {
        attr { absolutePositionAllZero(); backgroundColor(0x88000000); allCenter() }
        View {
            attr { width(ctx.pagerData.pageViewWidth - 40f); padding(18f); borderRadius(16f); backgroundColor(Color.WHITE) }
            Text { attr { text("添加持仓"); fontSize(18f); fontWeightBold(); color(0xFF172A43) } }
            Text { attr { text("输入6位代码，持仓与成本将实时计入风险"); fontSize(12f); color(0xFF7F8998); marginTop(6f) } }

            Text { attr { text("股票代码"); fontSize(12f); color(0xFF666666); marginTop(14f) } }
            Input {
                attr {
                    height(40f)
                    marginTop(4f)
                    fontSize(14f)
                    color(Color(0xFF333333))
                    editable(true)
                    text(ctx.addCodeText)
                    placeholder("例如 600519")
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(8f)
                }
                event { textDidChange(isSyncEdit = true) { ctx.addCodeText = it.text } }
            }

            View {
                attr { flexDirectionRow(); marginTop(12f) }
                View {
                    attr { flex(1f); marginRight(6f) }
                    Text { attr { text("持仓股数"); fontSize(12f); color(0xFF666666) } }
                    Input {
                        attr {
                            height(40f)
                            marginTop(4f)
                            fontSize(14f)
                            color(Color(0xFF333333))
                            editable(true)
                            text(ctx.addSharesText)
                            placeholder("100")
                            backgroundColor(0xFFF5F5F5)
                            borderRadius(8f)
                        }
                        event { textDidChange(isSyncEdit = true) { ctx.addSharesText = it.text } }
                    }
                }
                View {
                    attr { flex(1f); marginLeft(6f) }
                    Text { attr { text("成本价"); fontSize(12f); color(0xFF666666) } }
                    Input {
                        attr {
                            height(40f)
                            marginTop(4f)
                            fontSize(14f)
                            color(Color(0xFF333333))
                            editable(true)
                            text(ctx.addCostText)
                            placeholder("买入价")
                            backgroundColor(0xFFF5F5F5)
                            borderRadius(8f)
                        }
                        event { textDidChange(isSyncEdit = true) { ctx.addCostText = it.text } }
                    }
                }
            }

            vif({ ctx.addMessage.isNotEmpty() }) {
                Text { attr { text(ctx.addMessage); fontSize(12f); color(0xFFD32F2F); marginTop(8f) } }
            }

            View {
                attr { flexDirectionRow(); marginTop(16f) }
                View {
                    attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFFF0F2F5) }
                    event { click { ctx.showAddDialog = false } }
                    Text { attr { text("取消"); fontSize(13f); color(0xFF697586) } }
                }
                View { attr { width(10f) } }
                View {
                    attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFF0B2B50) }
                    event { click { ctx.confirmAdd() } }
                    Text { attr { text("确认添加"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.riskEditDialog(ctx: RiskCenterPage) {
    View {
        attr { absolutePositionAllZero(); backgroundColor(0x88000000); allCenter() }
        View {
            attr { width(ctx.pagerData.pageViewWidth - 40f); padding(18f); borderRadius(16f); backgroundColor(Color.WHITE) }
            Text { attr { text("调整持仓 · ${ctx.editName}"); fontSize(18f); fontWeightBold(); color(0xFF172A43) } }
            Text { attr { text(ctx.editCode); fontSize(12f); color(0xFF7F8998); marginTop(4f) } }

            View {
                attr { flexDirectionRow(); marginTop(14f) }
                View {
                    attr { flex(1f); marginRight(6f) }
                    Text { attr { text("持仓股数"); fontSize(12f); color(0xFF666666) } }
                    Input {
                        attr {
                            height(40f)
                            marginTop(4f)
                            fontSize(14f)
                            color(Color(0xFF333333))
                            editable(true)
                            text(ctx.editSharesText)
                            backgroundColor(0xFFF5F5F5)
                            borderRadius(8f)
                        }
                        event { textDidChange(isSyncEdit = true) { ctx.editSharesText = it.text } }
                    }
                }
                View {
                    attr { flex(1f); marginLeft(6f) }
                    Text { attr { text("成本价"); fontSize(12f); color(0xFF666666) } }
                    Input {
                        attr {
                            height(40f)
                            marginTop(4f)
                            fontSize(14f)
                            color(Color(0xFF333333))
                            editable(true)
                            text(ctx.editCostText)
                            backgroundColor(0xFFF5F5F5)
                            borderRadius(8f)
                        }
                        event { textDidChange(isSyncEdit = true) { ctx.editCostText = it.text } }
                    }
                }
            }

            Text { attr { text(ctx.editMessage); fontSize(12f); color(0xFFD32F2F); marginTop(8f) } }
            Text { attr { text("股数填0将清空持仓，保留自选和提醒"); fontSize(11f); color(0xFF999999); marginTop(8f) } }

            View {
                attr { flexDirectionRow(); marginTop(16f) }
                View {
                    attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFFF0F2F5) }
                    event { click { ctx.showEditDialog = false } }
                    Text { attr { text("取消"); fontSize(13f); color(0xFF697586) } }
                }
                View { attr { width(10f) } }
                View {
                    attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFF0B2B50) }
                    event { click { ctx.confirmEdit() } }
                    Text { attr { text("保存"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } }
                }
            }
        }
    }
}

private fun trimHoldNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else fmt2(v)
private fun percent(value: Double) = fmt2(value * 100.0) + "%"
private fun signedPercent(value: Double) = (if (value > 0) "+" else "") + percent(value)
private fun signedMoney(value: Double) = (if (value > 0) "+" else "") + "¥" + fmt2(value)
