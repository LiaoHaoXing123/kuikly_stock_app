package com.kuikly.stock.pages
import com.kuikly.stock.data.StockColors

import com.kuikly.stock.data.fmt1
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.*
import kotlin.math.abs

internal fun fundWindow(flows: List<FundFlowItem>, days: Int) = flows
    .filter { it.mainNet.isFinite() && it.mainRatio.isFinite() }
    .sortedBy { normalizedTradeDate(it.tradeDate) }.takeLast(days.coerceIn(1, 10))

internal fun fundEvidence(flows: List<FundFlowItem>, date: String): String {
    val row = flows.firstOrNull { normalizedTradeDate(it.tradeDate) == normalizedTradeDate(date) } ?: return "该日暂无资金流数据"
    return "${row.tradeDate} 主力净流入 ${fmtMoney(row.mainNet)}元 · 净占比 ${fmt1(row.mainRatio)}% · ${row.source}"
}

internal fun ViewContainer<*, *>.fundFlowCard(ctx: StockDetailPage) {
    val all = ctx.stockDetail?.fundFlow.orEmpty()
    View {
        attr { margin(4f, 12f, 4f, 12f); padding(14f); borderRadius(10f); backgroundColor(0xFFFFFFFF) }
        Text { attr { text("主力资金"); fontSize(15f); fontWeightBold(); color(0xFF26384A) } }
        if (all.isEmpty()) {
            Text { attr { text("暂无日级资金数据，更新行情后重试"); fontSize(12f); color(0xFF8A9099); marginTop(8f) } }
        } else {
            View {
                attr { flexDirectionRow(); marginTop(10f) }
                listOf(1 to "最近交易日", 5 to "近5日", 10 to "近10日").forEach { (days, title) ->
                    View {
                        attr { padding(7f, 10f, 7f, 10f); marginRight(6f); borderRadius(8f); backgroundColor(if (ctx.fundFlowDays == days) 0xFFE8F2FF else 0xFFF5F7FA) }
                        event { click { ctx.fundFlowDays = days } }
                        Text { attr { text(title); fontSize(11f); color(if (ctx.fundFlowDays == days) 0xFF1976D2 else 0xFF627083) } }
                    }
                }
            }
            vfor({ ObservableList(mutableListOf(ctx.fundFlowDays to ctx.selectedFundDate)) }) { (days, selected) ->
                // Kuikly requires exactly one root view for each reactive list item.
                View {
                val flows = fundWindow(all, days)
                val maxNet = flows.maxOfOrNull { abs(it.mainNet) }?.coerceAtLeast(1.0) ?: 1.0
                Text { attr { text("截至 ${flows.lastOrNull()?.tradeDate ?: "—"} · 实际 ${flows.size} 个交易日 · 单位：元"); fontSize(10f); color(0xFF8A9099); marginTop(8f) } }
                flows.asReversed().forEach { row ->
                    val tint = if (row.mainNet > 0) StockColors.UP else if (row.mainNet < 0) StockColors.DOWN else 0xFF8A9099
                    View {
                        attr { padding(8f); marginTop(4f); borderRadius(6f); backgroundColor(if (selected == row.tradeDate) 0xFFE8F2FF else 0xFFF8FAFC) }
                        event { click { ctx.selectedFundDate = row.tradeDate; ctx.focusEvidenceDate(row.tradeDate) } }
                        View {
                            attr { flexDirectionRow(); alignItemsCenter() }
                            Text { attr { text(row.tradeDate.takeLast(5)); width(52f); fontSize(12f); color(0xFF627083) } }
                            Text { attr { text(fmtMoney(row.mainNet)); flex(1f); fontSize(13f); fontWeightBold(); color(tint) } }
                            Text { attr { text("${fmt1(row.mainRatio)}%  ›"); fontSize(11f); color(tint) } }
                        }
                        View { attr { height(3f); marginTop(6f); width(((ctx.pagerData.pageViewWidth - 68f) * (abs(row.mainNet) / maxNet)).toFloat()); backgroundColor(tint); borderRadius(2f) } }
                    }
                }
                Text { attr { text("区间累计 ${fmtMoney(flows.sumOf { it.mainNet })}元"); fontSize(13f); fontWeightBold(); color(0xFF26384A); marginTop(10f) } }
                Text { attr { text("${flows.map { it.source }.distinct().joinToString(" / ")} · 点击日期定位日K"); fontSize(10f); lineHeight(16f); color(0xFF8A9099); marginTop(6f) } }
                detailAction("结合这段资金流问 AI") {
                    val question = buildString {
                        append("请结合 ${ctx.stockCode} 最近 ${flows.size} 个交易日的资金与K线分析，说明数据日期与局限。\n")
                        flows.forEach { append(fundEvidence(flows, it.tradeDate)).append('\n') }
                        append("这些是日级数据，不能推断分钟资金或缺失的超大/大/中/小单。")
                    }
                    ctx.openModule(AppRoutes.CHAT, JSONObject().apply { put("detail_question", question) })
                }
                }
            }
        }
    }
}
