package com.kuikly.stock.pages

import com.kuikly.stock.data.AlertEngine
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.risk.HoldingRiskLine
import com.kuikly.stock.risk.HoldingSnapshot
import com.kuikly.stock.risk.PortfolioRiskCalculator
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

@Page(AppRoutes.RISK)
class RiskCenterPage : Pager() {
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

    override fun didInit() {
        super.didInit()
        reload()
    }

    internal fun reload() {
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
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); flexDirectionColumn(); backgroundColor(0xFFF4F7FB) }
                pageTitleBar(ctx, "组合风险", "基于本地持仓与行情计算") { ctx.reload() }
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true); padding(16f) }
                    riskOverview(ctx)
                    vif({ ctx.emptyState }) { riskEmpty() }
                    vif({ !ctx.emptyState }) {
                        riskSectionTitle("风险提示")
                        vif({ ctx.riskMessages.isEmpty() }) { calmRiskState() }
                        vfor({ ctx.riskMessages }) { message -> riskMessage(message) }
                        riskSectionTitle("持仓拆解")
                        vfor({ ctx.lines }) { line -> holdingRiskRow(line) }
                    }
                    riskSectionTitle("已触发提醒")
                    vif({ ctx.alertMessages.isEmpty() }) { quietAlertState() }
                    vfor({ ctx.alertMessages }) { message -> riskMessage(message) }
                    View { attr { height(18f) } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.riskOverview(ctx: RiskCenterPage) {
    View {
        attr { padding(18f); borderRadius(18f); backgroundColor(0xFF0B2B50) }
        Text { attr { text("组合市值"); fontSize(12f); color(0xFF9EC8F5) } }
        Text { attr { text(ctx.marketValue); fontSize(27f); fontWeightBold(); color(Color.WHITE); marginTop(6f) } }
        Text { attr { text("累计盈亏  ${ctx.pnl}"); fontSize(13f); color(ctx.pnlColor); marginTop(7f) } }
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
    }
}

private fun ViewContainer<*, *>.riskSectionTitle(title: String) {
    Text { attr { text(title); fontSize(17f); fontWeightBold(); color(0xFF172A43); margin(top = 20f, bottom = 10f) } }
}

private fun ViewContainer<*, *>.riskEmpty() {
    View {
        attr { padding(24f); marginTop(14f); borderRadius(15f); backgroundColor(Color.WHITE); alignItems(FlexAlign.CENTER) }
        Text { attr { text("还没有持仓数据"); fontSize(16f); fontWeightBold(); color(0xFF24364D) } }
        Text { attr { text("请在自选页设置持仓股数与成本，风险中心不会用 0 伪装有效市值。"); fontSize(12f); lineHeight(19f); color(0xFF7F8998); marginTop(7f); textAlignCenter() } }
    }
}

private fun ViewContainer<*, *>.calmRiskState() {
    View { attr { padding(14f); borderRadius(12f); backgroundColor(0xFFEAF8F0) }; Text { attr { text("暂未命中高集中度或高回撤规则"); fontSize(13f); color(0xFF18794E) } } }
}

private fun ViewContainer<*, *>.quietAlertState() {
    View { attr { padding(14f); borderRadius(12f); backgroundColor(Color.WHITE) }; Text { attr { text("暂无触发中的价格提醒"); fontSize(13f); color(0xFF788494) } } }
}

private fun ViewContainer<*, *>.riskMessage(message: String) {
    View { attr { padding(13f); marginBottom(8f); borderRadius(12f); backgroundColor(0xFFFFF3E8) }; Text { attr { text(message); fontSize(13f); lineHeight(19f); color(0xFF8B4C12) } } }
}

private fun ViewContainer<*, *>.holdingRiskRow(line: HoldingRiskLine) {
    View {
        attr { padding(14f); marginBottom(9f); borderRadius(14f); backgroundColor(Color.WHITE) }
        View { attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            View { attr { flex(1f) }
                Text { attr { text(line.name); fontSize(15f); fontWeightBold(); color(0xFF1C3048) } }
                Text { attr { text("${line.code} · ${line.industry}"); fontSize(11f); color(0xFF8993A1); marginTop(2f) } }
            }
            Text { attr { text(percent(line.weight)); fontSize(15f); fontWeightBold(); color(if (line.weight >= 0.5) 0xFFD35454 else 0xFF315C87) } }
        }
        Text { attr { text("市值 ¥${fmt2(line.marketValue)}  ·  盈亏 ${signedMoney(line.pnl)} (${signedPercent(line.pnlRate)})"); fontSize(12f); color(0xFF647386); marginTop(9f) } }
    }
}

private fun percent(value: Double) = fmt2(value * 100.0) + "%"
private fun signedPercent(value: Double) = (if (value > 0) "+" else "") + percent(value)
private fun signedMoney(value: Double) = (if (value > 0) "+" else "") + "¥" + fmt2(value)
