package com.kuikly.stock.pages

import com.kuikly.stock.data.SectorSnapshot
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

internal fun ViewContainer<*, *>.sectorCard(ctx: StockDetailPage) {
    vif({ ctx.sectorSnapshot != null }) {
        View {
            attr { margin(4f, 12f, 4f, 12f); padding(12f); backgroundColor(0xFFFFFFFF); borderRadius(10f) }
            View {
                attr { flexDirectionRow(); alignItemsCenter(); minHeight(36f) }
                event { click { ctx.sectorExpanded = !ctx.sectorExpanded } }
                Text { attr { text("官方板块 · ${ctx.sectorSnapshot?.board?.boardName.orEmpty()}"); fontSize(13f); fontWeightBold(); flex(1f); color(0xFF26384A) } }
                Text {
                    attr {
                        text(ctx.sectorSnapshot?.board?.changePercent?.let { fmtSignedPct(it) } ?: "—")
                        fontSize(13f); fontWeightBold()
                        color(if ((ctx.sectorSnapshot?.board?.changePercent ?: 0.0) >= 0) 0xFFEF4444 else 0xFF10B981)
                    }
                }
                Text { attr { text(if (ctx.sectorExpanded) " 收起 ∧" else " 排行 ›"); fontSize(12f); color(0xFF1976D2) } }
            }
            Text {
                attr {
                    val b = ctx.sectorSnapshot?.board
                    text(buildString {
                        b?.leader?.let { append("领涨 $it${b.leaderChange?.let { c -> " ${fmtSignedPct(c)}" } ?: ""} · ") }
                        b?.let { append("${it.upCount}涨${it.downCount}跌") }
                        b?.let { if (it.totalMv != null) append(" · 市值 ${fmtMvYi(it.totalMv)}") }
                        b?.let { append(" · 快照 ${it.fetchDate}") }
                    }.ifEmpty { "官方行业板块 · 当日快照" })
                    fontSize(11f); lineHeight(18f); color(0xFF627083)
                }
            }
            Text {
                attr {
                    val s = ctx.sectorSnapshot
                    text("个股相对板块 ${s?.relative(ctx.stockCode)?.let { "${fmtSigned2(it)}个百分点" } ?: "—"} · 板块内排名 ${s?.rankOf(ctx.stockCode)?.let { "${it}/${s.current.size}" } ?: "—"}")
                    fontSize(11f); lineHeight(18f); color(0xFF627083); marginTop(2f)
                }
            }
            vif({ ctx.sectorExpanded }) {
                View {
                    attr { flexDirectionRow(); marginTop(8f) }
                    detailAction("切换涨幅排序") { ctx.sectorAscending = !ctx.sectorAscending }
                    detailAction("板块对照问 AI") {
                        ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("chat_main", JSONObject().apply {
                            put("detail_question", "请分析 ${ctx.stockCode} 在其所属官方板块中的相对表现。${ctx.sectorSnapshot?.evidence(ctx.stockCode)}")
                        })
                    }
                }
                Text { attr { text(if (ctx.sectorAscending) "涨幅从低到高 · 点击股票查看详情" else "涨幅从高到低 · 点击股票查看详情"); fontSize(10f); color(0xFF8A9099); marginTop(6f) } }
                Scroller {
                    attr { height(220f); flexDirectionColumn(); marginTop(4f) }
                    vfor({
                        val items = ctx.sectorSnapshot?.current.orEmpty().sortedBy { it.changePercent ?: 0.0 }
                        ObservableList((if (ctx.sectorAscending) items else items.reversed()).toMutableList())
                    }) { member ->
                        View {
                            attr { minHeight(42f); flexDirectionRow(); alignItemsCenter(); padding(6f); backgroundColor(if (member.code == ctx.stockCode) 0xFFE8F2FF else 0xFFFFFFFF) }
                            event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("stock_detail", JSONObject().apply { put("code", member.code) }) } }
                            Text { attr { text("${member.name ?: member.code}\n${member.code}"); fontSize(11f); lineHeight(16f); flex(1f); color(0xFF26384A) } }
                            Text { attr { text(member.price?.let { fmt2(it) } ?: "—"); fontSize(12f); width(64f); color(0xFF627083) } }
                            Text { attr { text(member.changePercent?.let { fmtSignedPct(it) } ?: "—"); fontSize(12f); color(if ((member.changePercent ?: 0.0) >= 0) 0xFFEF4444 else 0xFF10B981) } }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtMvYi(v: Double): String {
    val yi = v / 1e8
    return if (yi >= 10000) fmt2(yi / 1e4) + "万亿" else fmt2(yi) + "亿"
}
