package com.kuikly.stock.pages
import com.kuikly.stock.data.StockColors

import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import com.kuikly.stock.data.fmtSigned2
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.*

internal fun ViewContainer<*, *>.industryCard(ctx: StockDetailPage) {
    vif({ ctx.industrySnapshot != null }) {
        View {
            attr { margin(4f, 12f, 4f, 12f); padding(12f); backgroundColor(0xFFFFFFFF); borderRadius(10f) }
            View {
                attr { flexDirectionRow(); alignItemsCenter(); minHeight(36f) }
                event { click { ctx.industryExpanded = !ctx.industryExpanded } }
                Text { attr { text("同业 · ${ctx.industrySnapshot?.name.orEmpty()}"); fontSize(13f); fontWeightBold(); flex(1f); color(0xFF26384A) } }
                Text { attr { text(if (ctx.industryExpanded) "收起 ∧" else "排行 ›"); fontSize(12f); color(0xFF1976D2) } }
            }
            Text {
                attr {
                    val snapshot = ctx.industrySnapshot
                    text("样本均值 ${snapshot?.average?.let { fmtSignedPct(it) } ?: "—"} · 个股相对 ${snapshot?.relative(ctx.stockCode)?.let { "${fmtSigned2(it)}百分点" } ?: "—"}")
                    fontSize(11f); lineHeight(18f); color(0xFF627083)
                }
            }
            vif({ ctx.industryExpanded }) {
                Text { attr { text("${ctx.industrySnapshot?.date} · 同日有效 ${ctx.industrySnapshot?.current?.size}/${ctx.industrySnapshot?.members?.size} 只 · 等权样本，非板块指数"); fontSize(10f); lineHeight(16f); color(0xFF8A9099); marginTop(6f) } }
                View {
                    attr { flexDirectionRow() }
                    detailAction("切换涨幅排序") { ctx.industryAscending = !ctx.industryAscending }
                    detailAction("同业对照问 AI") {
                        ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("chat_main", JSONObject().apply {
                            put("detail_question", "请分析 ${ctx.stockCode} 的同业相对强弱。${ctx.industrySnapshot?.evidence(ctx.stockCode)}")
                        })
                    }
                }
                Text { attr { text(if (ctx.industryAscending) "涨幅从低到高 · 点击股票查看详情" else "涨幅从高到低 · 点击股票查看详情"); fontSize(10f); color(0xFF8A9099); marginTop(8f) } }
                Scroller {
                    attr { height(220f); flexDirectionColumn(); marginTop(4f) }
                    vfor({
                        val items = ctx.industrySnapshot?.current.orEmpty().sortedBy { it.quote.changePercent }
                        ObservableList((if (ctx.industryAscending) items else items.reversed()).toMutableList())
                    }) { member ->
                        val stock = member.quote
                        View {
                            attr { minHeight(42f); flexDirectionRow(); alignItemsCenter(); padding(6f); backgroundColor(if (stock.code == ctx.stockCode) 0xFFE8F2FF else 0xFFFFFFFF) }
                            event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("stock_detail", JSONObject().apply { put("code", stock.code) }) } }
                            Text { attr { text("${stock.name ?: stock.code}\n${stock.code}"); fontSize(11f); lineHeight(16f); flex(1f); color(0xFF26384A) } }
                            Text { attr { text(stock.price?.let { fmt2(it) } ?: "—"); fontSize(12f); width(64f); color(0xFF627083) } }
                            Text { attr { text(stock.changePercent?.let { fmtSignedPct(it) } ?: "—"); fontSize(12f); color(if ((stock.changePercent ?: 0.0) >= 0) StockColors.UP else StockColors.DOWN) } }
                        }
                    }
                }
            }
        }
    }
}
