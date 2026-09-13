// AI 复盘卡：把分析历史与之后的真实行情对照，本地程序计算战绩。
// 自 StockDetailPage.kt 拆出的正文卡片之一，随 detailEpoch 重建。

package com.kuikly.stock.pages

import com.kuikly.stock.data.AiReviewEngine
import com.kuikly.stock.data.AiReviewEntry
import com.kuikly.stock.data.AnalysisSnapshot
import com.kuikly.stock.data.StockColors
import com.kuikly.stock.data.exportTimestampString
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.*
import com.kuikly.stock.ui.theme.AppColor

internal fun ViewContainer<*, *>.aiReviewCard(
    ctx: StockDetailPage,
    onSelect: (AnalysisSnapshot) -> Unit,
) {
    val summary = AiReviewEngine.review(ctx.analysisState.records, ctx.stockDetail?.kline.orEmpty())
    if (summary.entries.isEmpty()) return

    View {
        attr {
            flexDirectionColumn()
            padding(12f)
            margin(6f, 12f, 6f, 12f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        Text {
            attr {
                text("AI 复盘 · 战绩")
                fontSize(14f)
                fontWeightBold()
                color(AppColor.TEXT_DEEP)
            }
        }
        Text {
            attr {
                val rate = summary.hitRatePct
                text(
                    if (summary.evaluatedCount == 0) "已有 ${summary.entries.size} 条分析，行情走出后自动验证"
                    else "可复盘 ${summary.evaluatedCount} 条 · 方向命中 ${summary.directionHitCount} 条" +
                        (rate?.let { "（$it%）" } ?: "")
                )
                fontSize(12f)
                color(AppColor.TEXT_SUB_DEEP)
                marginTop(6f)
            }
        }
        Text {
            attr {
                text("本地程序计算，非 AI 生成；短线按 10 个交易日评估")
                fontSize(10f)
                color(AppColor.TEXT_SUB_DEEP)
                marginTop(2f)
            }
        }

        vif({ summary.evaluatedCount > 0 }) {
            detailAction("展开 / 收起明细") { ctx.reviewExpanded = !ctx.reviewExpanded }
        }

        vif({ ctx.reviewExpanded }) {
            vfor({ ObservableList(summary.entries.toMutableList()) }) { entry ->
                val record = ctx.analysisState.records.firstOrNull { it.id == entry.id }
                View {
                    attr { flexDirectionColumn(); marginTop(8f); marginBottom(2f) }
                    Text {
                        attr {
                            text(reviewTitle(entry))
                            fontSize(11f)
                            color(reviewBiasColor(entry.bias))
                        }
                    }
                    Text {
                        attr {
                            text(reviewDetail(entry))
                            fontSize(11f)
                            lineHeight(17f)
                            color(AppColor.TEXT_SUB_DEEP)
                            marginTop(2f)
                        }
                    }
                    if (record != null) {
                        detailAction("查看该次分析") { onSelect(record) }
                    }
                }
            }
        }
    }
}

private fun reviewTitle(e: AiReviewEntry): String =
    "${exportTimestampString(e.generatedAt)} · ${e.bias}（置信${e.confidence}）· ${e.horizon} · ${e.status}"

private fun reviewDetail(e: AiReviewEntry): String {
    if (e.basePrice == null) return "行情快照缺失，无法对照复盘"
    if (e.elapsedDays <= 0) return "基准 ${e.baseDate} 收盘 ${fmt2(e.basePrice)}，尚无后续交易日"
    val pct = e.returnPct?.let { fmtSignedPct(it) } ?: "-"
    val parts = mutableListOf(
        "基准 ${e.baseDate} 收盘 ${fmt2(e.basePrice)} → 最新 ${e.latestDate.orEmpty()} ${pct}",
        "已走 ${e.elapsedDays}/${e.windowDays} 个交易日",
    )
    e.supportHeld?.let { parts.add(if (it) "支撑未破" else "支撑已破") }
    e.resistanceTouched?.takeIf { it }?.let { parts.add("触及压力位") }
    e.targetReached?.takeIf { it }?.let { parts.add("目标达成") }
    e.stopHit?.takeIf { it }?.let { parts.add("触发止损位") }
    return parts.joinToString(" · ")
}

private fun reviewBiasColor(bias: String): Long = when (bias) {
    "偏多" -> StockColors.UP
    "偏空" -> StockColors.DOWN
    else -> StockColors.FLAT
}
