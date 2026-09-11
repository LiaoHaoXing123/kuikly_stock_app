// 个股详情页 —— AI 分析相关 UI 与解析。
// 自 StockDetailPage.kt 拆出：AI 价位/信号解析、立场条、信号卡、分析卡片渲染与 Markdown 导出。
// pricePill 为该模块私有，仅本文件使用。

package com.kuikly.stock.pages

import com.kuikly.stock.data.StockColors

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.TextAlign
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.nowMillis
import com.kuikly.stock.data.AIVerdict
import com.kuikly.stock.ai.protocol.VerdictSynthesizer
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.data.ConclusionAlertFactory
import com.kuikly.stock.data.PriceAlertRule
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuiklybase.KuiklyMarkdown
import com.kuikly.stock.data.fmt0
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmt3
import com.kuikly.stock.data.fmtSigned2
import com.kuikly.stock.data.fmtSignedPct
import kotlin.math.abs

internal data class AIPriceLevel(
    val label: String,
    val price: Double,
    val color: Long,
    val type: String // support, resistance, target, stopLoss
)

internal fun parseAIPriceLevels(analysis: AIAnalysisData?): List<AIPriceLevel> {
    if (analysis == null) return emptyList()
    val levels = mutableListOf<AIPriceLevel>()
    for (card in analysis.cards) {
        val type = card["type"] as? String ?: continue
        if (type != "suggestion_card" && type != "trend_card") continue
        // 兼容多种字段名
        fun addLevel(key: String, label: String, color: Long, t: String) {
            val raw = card[key] ?: return
            val price = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull() ?: return
                else -> return
            }
            if (price.isFinite() && price > 0) {
                // 去重
                if (levels.none { it.price == price && it.type == t }) {
                    levels.add(AIPriceLevel(label, price, color, t))
                }
            }
        }
        addLevel("support_price", "支撑位", 0xFF2E9E5B, "support")
        addLevel("support_value", "支撑位", 0xFF2E9E5B, "support")
        addLevel("resistance_price", "压力位", 0xFFD64545, "resistance")
        addLevel("resistance_value", "压力位", 0xFFD64545, "resistance")
        addLevel("target_price", "目标价", 0xFF0E67D1, "target")
        addLevel("stop_loss", "止损价", 0xFFA56100, "stopLoss")
        // 有些模板用下划线不同
        addLevel("target", "目标价", 0xFF0E67D1, "target")
        addLevel("stop", "止损价", 0xFFA56100, "stopLoss")
    }
    return levels.sortedBy { it.price }
}

// -----------------------------------------------------------------------------
// P0-2: 行情区 AI 注解标签 (放置于实时行情涨跌幅右侧)
// -----------------------------------------------------------------------------
internal fun ViewContainer<*, *>.aiBiasChip(ctx: StockDetailPage) {
    View {
        attr {
            marginLeft(8f)
            height(20f)
            borderRadius(10f)
            allCenter()
            paddingLeft(7f)
            paddingRight(7f)
            val v = ctx.effectiveVerdict
            if (v != null) {
                backgroundColor(v.chipColor)
                border(Border(0.8f, BorderStyle.SOLID, Color((v.colorValue and 0x00FFFFFF) or 0x44000000)))
            } else {
                backgroundColor(0x0A000000)
                border(Border(0.8f, BorderStyle.DASHED, Color(0xFFC2C7D0)))
            }
        }
        event {
            click {
                if (ctx.effectiveVerdict == null) ctx.triggerAIAnalysis() else ctx.jumpToAiSection("level_card")
            }
        }

        Text {
            attr {
                text(
                    when {
                        ctx.isAnalyzing -> "AI 研判中…"
                        ctx.effectiveVerdict != null -> "AI ${ctx.effectiveVerdict!!.bias}"
                        else -> "⟡ AI 研判"
                    }
                )
                fontSize(10f)
                fontWeightBold()
                color(ctx.effectiveVerdict?.colorValue ?: 0xFF6B7280)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// P0-1: K 线顶部嵌入式 AI 观点条（支持折叠展开）
// -----------------------------------------------------------------------------
internal fun ViewContainer<*, *>.aiVerdictBar(ctx: StockDetailPage, compact: Boolean = false) {
    val v = ctx.effectiveVerdict
    val loading = ctx.isAnalyzing
    val expanded = !compact && ctx.verdictExpanded && v != null
    val data = ctx.aiAnalysis

    View {
        attr {
            minHeight(34f)
            flexDirectionColumn()
            backgroundColor(v?.tintColor ?: Color(0x0D000000))
            borderRadius(if (compact) 0f else 6f)
            marginBottom(if (compact) 0f else 4f)
            paddingLeft(10f)
            paddingRight(10f)
            if (compact) {
                boxShadow(BoxShadow(0f, 2f, 6f, Color(0x22000000)))
            }
        }
        event {
            click {
                when {
                    v == null && !loading -> ctx.triggerAIAnalysis()
                    compact -> ctx.jumpToAiSection()
                    else -> ctx.verdictExpanded = !ctx.verdictExpanded
                }
            }
        }

        // 第一行
        View {
            attr { height(34f); flexDirectionRow(); alignItemsCenter() }
            when {
                loading -> Text { attr { text("AI 正在研判…"); fontSize(11f); color(Color(0xFF8A9099)) } }
                v == null -> Text { attr { text("⟡ 点击生成 AI 观点"); fontSize(11f); color(Color(0xFF5B7FFF)); fontWeightBold() } }
                else -> {
                    View {
                        attr {
                            backgroundColor(v.color)
                            borderRadius(3f)
                            paddingLeft(5f)
                            paddingRight(5f)
                            height(17f)
                            allCenter()
                        }
                        Text { attr { text("AI ${v.bias}"); fontSize(10f); color(Color.WHITE); fontWeightBold() } }
                    }
                    if (v.confidence == "低" || data?.degraded == true) {
                        Text { attr { text("参考"); fontSize(9f); color(Color(0xFF8A9099)); marginLeft(4f) } }
                    }
                    Text {
                        attr {
                            text(v.oneLiner)
                            fontSize(11f)
                            color(Color(0xFF2A2E36))
                            marginLeft(6f)
                            flex(1f)
                            lines(1)
                        }
                    }
                    if (!compact) {
                        Text { attr { text(if (expanded) "▲" else "▼"); fontSize(9f); color(Color(0xFF8A9099)) } }
                    } else {
                        Text { attr { text("详情 ›"); fontSize(10f); color(Color(0xFF5B7FFF)) } }
                    }
                }
            }
        }

        // 展开区
        if (!compact && data != null) {
            Text {
                attr {
                    val latest = ctx.stockDetail?.kline?.lastOrNull()?.tradeDate.orEmpty()
                    text(analysisDateLabel(data.dataDate, latest))
                    fontSize(10f)
                    lineHeight(16f)
                    color(0xFF727B89)
                    marginBottom(6f)
                }
            }
        }
        if (expanded && v != null) {
            View {
                attr { flexDirectionRow(); flexWrapWrap(); alignItemsCenter(); marginTop(4f) }
                v.supportValue?.let { p ->
                    pricePill("支撑", p, StockColors.DOWN) { ctx.focusKline(KlineFocus.Price(p, "AI支撑", StockColors.DOWN)) }
                }
                v.resistanceValue?.let { p ->
                    pricePill("压力", p, StockColors.UP) { ctx.focusKline(KlineFocus.Price(p, "AI压力", StockColors.UP)) }
                }
                v.targetValue?.let { p ->
                    pricePill("目标", p, 0xFFE68A45) { ctx.focusKline(KlineFocus.Price(p, "AI目标", 0xFFE68A45)) }
                }
                v.stopLossValue?.let { p ->
                    pricePill("止损", p, 0xFF8A9099) { ctx.focusKline(KlineFocus.Price(p, "AI止损", 0xFF8A9099)) }
                }
            }
            View {
                attr { flexDirectionRow(); alignItemsCenter(); height(26f) }
                Text { attr { text("${v.horizon} · 置信${v.confidence}"); fontSize(10f); color(Color(0xFF8A9099)); flex(1f) } }
                Text {
                    attr { text("查看完整分析 ›"); fontSize(10f); color(Color(0xFF5B7FFF)) }
                    event { click { ctx.jumpToAiSection() } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.pricePill(label: String, value: Double, colorValue: Long, onTap: () -> Unit) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
            height(22f)
            borderRadius(11f)
            paddingLeft(8f)
            paddingRight(8f)
            marginRight(6f)
            marginBottom(6f)
            backgroundColor(Color((colorValue and 0x00FFFFFF) or 0x1F000000))
        }
        event { click { onTap() } }
        Text { attr { text(label); fontSize(10f); color(Color(colorValue)) } }
        Text {
            attr {
                text(fmt2(value))
                fontSize(11f)
                fontWeightBold()
                color(Color(colorValue))
                marginLeft(3f)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// P0-3: signal_card v2 渲染（消费对象数组，支持双向联动）
// -----------------------------------------------------------------------------
internal fun ViewContainer<*, *>.signalCardV2(ctx: StockDetailPage, card: Map<String, Any?>) {
    val title = card["title"] as? String ?: "技术信号"
    val signals = (card["signals"] as? List<*>)?.mapNotNull { item ->
        when (item) {
            is Map<*, *> -> item.entries.associate { (k, v) -> k.toString() to v }
            is String -> mapOf("text" to item)
            else -> null
        }
    }.orEmpty()
    val range = ctx.klineVisibleRange
    val pulsing = ctx.highlightCardType == "signal_card"

    View {
        attr {
            backgroundColor(Color.WHITE)
            borderRadius(8f)
            padding(12f)
            marginBottom(10f)
            if (pulsing) border(Border(1.5f, BorderStyle.SOLID, Color(0xFF5B7FFF)))
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginBottom(6f) }
            Text { attr { text(title); fontSize(14f); fontWeightBold(); color(Color(0xFF2A2E36)); flex(1f) } }
            Text { attr { text("点击信号定位K线"); fontSize(10f); color(Color(0xFF8A9099)) } }
        }

        signals.forEach { s ->
            val text = s["text"] as? String ?: return@forEach
            val dir = s["direction"] as? String ?: "中性"
            val start = s["start_date"] as? String
            val end = s["end_date"] as? String ?: start
            val dirColor = directionColorValue(dir)
            val clickable = start != null
            val inView = start != null && range != null &&
                    start >= range.first && start <= range.second

            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingTop(6f)
                    paddingBottom(6f)
                    paddingLeft(6f)
                    paddingRight(6f)
                    borderRadius(4f)
                    if (inView) {
                        backgroundColor(Color(0x0F5B7FFF))
                        border(Border(1f, BorderStyle.SOLID, Color(0x335B7FFF)))
                    }
                }
                if (clickable) {
                    event {
                        click {
                            ctx.focusKline(KlineFocus.Range(start!!, end!!, text, dirColor))
                        }
                    }
                }
                View { attr { width(3f); height(12f); backgroundColor(Color(dirColor)); borderRadius(2f) } }
                Text {
                    attr {
                        text(text)
                        fontSize(12f)
                        marginLeft(8f)
                        flex(1f)
                        color(if (clickable) Color(0xFF2A2E36) else Color(0xFF6B7280))
                    }
                }
                if (clickable) {
                    Text { attr { text(if (inView) "● 在图中" else "定位 ›"); fontSize(10f); color(Color(0xFF5B7FFF)) } }
                }
            }
        }
    }
}

internal fun parseAISignals(analysis: AIAnalysisData?): List<String> {
    if (analysis == null) return emptyList()
    val signals = mutableListOf<String>()
    for (card in analysis.cards) {
        if (card["type"] == "signal_card") {
            val list = card["signals"] as? List<*>
            list?.forEach { s -> s?.toString()?.let { if (it.isNotBlank()) signals.add(it) } }
        }
    }
    return signals
}

internal fun ViewContainer<*, *>.aiAnalysisCards(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 12f, 12f)
        }

        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                marginBottom(8f)
            }

            Text {
                attr {
                    text("AI 智能解读")
                    fontSize(15f)
                    fontWeightBold()
                    color(0xFF333333)
                    flex(1f)
                }
            }

            vif({ ctx.aiAnalysis != null }) {
                Text {
                    attr {
                        text("${ctx.aiAnalysis!!.cards.size}张卡片")
                        fontSize(11f)
                        color(0xFF999999)
                        marginRight(8f)
                    }
                }
            }

            vif({ !ctx.isAnalyzing }) {
                View {
                    attr {
                        padding(top = 4f, left = 10f, bottom = 4f, right = 10f)
                        backgroundColor(0xFF1976D2)
                        borderRadius(12f)
                    }
                    event {
                        click {
                            ctx.triggerAIAnalysis()
                        }
                    }
                    Text {
                        attr {
                            text(if (ctx.aiAnalysis == null) "开始分析" else "刷新")
                            fontSize(11f)
                            color(0xFFFFFFFF)
                            fontWeightBold()
                        }
                    }
                }
            }
        }

        vif({ ctx.isAnalyzing }) {
            analyzingView(ctx)
        }
        velseif({ ctx.aiAnalysis == null }) {
            notAnalyzedView(ctx)
        }
        velse {
            // 结构化卡片渲染
            vfor({ ObservableList(listOfNotNull(ctx.aiAnalysis).map { Triple(it, ctx.aiExpandedKeys.toList(), ctx.highlightCardType) }.toMutableList()) }) { (analysis, _, _) ->
            View {
                attr { flexDirectionColumn() }
                aiEvidencePanel({ ctx.aiAnalysis }) { ctx.focusEvidenceDate(it) }
                // 按类型分组，固定顺序：趋势、信号、建议、风险、总结
                val orderedTypes = listOf("trend_card", "signal_card", "suggestion_card", "risk_card", "summary_card")
                val grouped = analysis.cards.filter { it["type"] != "evidence_card" }.groupBy { it["type"] as? String ?: "unknown" }
                orderedTypes.forEach { t ->
                    grouped[t]?.forEachIndexed { idx, card ->
                        renderAIAnalysisCard(ctx, card, "${t}_$idx")
                    }
                }
                // 其他未知类型
                grouped.filterKeys { it !in orderedTypes }.values.flatten().forEachIndexed { idx, card ->
                    renderAIAnalysisCard(ctx, card, "other_$idx")
                }

                // 联动提示
                View {
                    attr {
                        flexDirectionColumn()
                        marginTop(10f)
                        padding(10f, 12f, 10f, 12f)
                        backgroundColor(0xFFF1F7FF)
                        borderRadius(10f)
                    }
                    Text {
                        attr {
                            text("联动交互说明")
                            fontSize(12f)
                            fontWeightBold()
                            color(0xFF1976D2)
                        }
                    }
                    Text {
                        attr {
                            text("· 点击AI价位卡片 → K线标注虚线\n· 点击K线 → 查看与AI价位的距离\n· 设提醒 → 写入自选盯盘，行情刷新时触发\n· 周K/月K → 聚合查看中长期趋势")
                            fontSize(11f)
                            color(0xFF666666)
                            marginTop(4f)
                            lineHeight(16f)
                        }
                    }
                }
            }
        }
    }
    }
}

internal fun ViewContainer<*, *>.renderAIAnalysisCard(ctx: StockDetailPage, card: Map<String, Any?>, key: String) {
    val type = card["type"] as? String ?: "unknown"
    val title = card["title"] as? String ?: when (type) {
        "trend_card" -> "趋势研判"
        "signal_card" -> "技术信号"
        "suggestion_card" -> "操作建议"
        "risk_card" -> "风险提示"
        "summary_card" -> "总结"
        else -> "分析"
    }

    when (type) {
        "trend_card" -> {
            val content = card["content"] as? String ?: ""
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(0xFFFFFFFF)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1f, BorderStyle.SOLID, Color(0xFFE3F2FD)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    View {
                        attr {
                            width(4f)
                            height(16f)
                            backgroundColor(0xFF1976D2)
                            borderRadius(2f)
                            marginRight(8f)
                        }
                    }
                    Text {
                        attr {
                            text(title)
                            fontSize(14f)
                            fontWeightBold()
                            color(0xFF1976D2)
                            flex(1f)
                        }
                    }
                    View {
                        attr {
                            padding(3f, 8f, 3f, 8f)
                            backgroundColor(0xFFE3F2FD)
                            borderRadius(10f)
                        }
                        event { click { ctx.toggleAISection(key) } }
                        Text {
                            attr {
                                text(if (ctx.isAIExpanded(key)) "收起" else "展开")
                                fontSize(11f)
                                color(0xFF1976D2)
                            }
                        }
                    }
                }
                Text {
                    attr {
                        text(content)
                        fontSize(13f)
                        color(0xFF333333)
                        marginTop(8f)
                        lineHeight(19f)
                    }
                }
                vif({ ctx.isAIExpanded(key) }) {
                    val bias = card["bias"] as? String ?: ""
                    if (bias.isNotEmpty()) {
                        View {
                            attr {
                                marginTop(8f)
                                padding(8f, 10f, 8f, 10f)
                                backgroundColor(0xFFF5F5F5)
                                borderRadius(8f)
                            }
                            Text {
                                attr {
                                    text("倾向：$bias")
                                    fontSize(12f)
                                    color(0xFF666666)
                                }
                            }
                        }
                    }
                }
            }
        }
        "signal_card" -> {
            signalCardV2(ctx, card)
        }
        "suggestion_card" -> {
            val suggestion = card["suggestion"] as? String ?: "-"
            val targetPrice = parseCardPrice(card["target_price"])
            val stopLoss = parseCardPrice(card["stop_loss"])
            val support = parseCardPrice(card["support_price"] ?: card["support_value"])
            val resistance = parseCardPrice(card["resistance_price"] ?: card["resistance_value"])
            val currentPrice = ctx.stockDetail?.realtime?.price ?: 0.0

            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(0xFFFFFFFF)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1.2f, BorderStyle.SOLID, Color(if (ctx.highlightCardType == "level_card") 0xFF5B7FFF else 0xFF1976D2)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    Text {
                        attr {
                            text("$title")
                            fontSize(14f)
                            fontWeightBold()
                            color(0xFF1976D2)
                            flex(1f)
                        }
                    }
                    View {
                        attr {
                            padding(4f, 10f, 4f, 10f)
                            backgroundColor(0xFF1976D2)
                            borderRadius(12f)
                        }
                        event { click { ctx.toggleAISection(key) } }
                        Text {
                            attr {
                                text(if (ctx.isAIExpanded(key)) "收起详情" else "展开价位")
                                fontSize(11f)
                                color(0xFFFFFFFF)
                                fontWeightBold()
                            }
                        }
                    }
                }
                Text {
                    attr {
                        text(suggestion)
                        fontSize(13f)
                        color(0xFF333333)
                        marginTop(8f)
                        lineHeight(19f)
                    }
                }

                // 价位网格
                View {
                    attr { flexDirectionColumn(); marginTop(10f) }
                    if (resistance != null) priceLevelRow(ctx, "压力位", resistance, currentPrice, 0xFFD64545, 0)
                    if (support != null) priceLevelRow(ctx, "支撑位", support, currentPrice, 0xFF2E9E5B, 1)
                    if (targetPrice != null) priceLevelRow(ctx, "目标价", targetPrice, currentPrice, 0xFF0E67D1, 0)
                    if (stopLoss != null) priceLevelRow(ctx, "止损价", stopLoss, currentPrice, 0xFFA56100, 1)
                }

                vif({ ctx.isAIExpanded(key) }) {
                    View {
                        attr {
                            marginTop(10f)
                            padding(10f)
                            backgroundColor(0xFFF5F5F5)
                            borderRadius(8f)
                        }
                        Text {
                            attr {
                                text("操作说明：点击价位可在K线标注虚线，设提醒后可在自选页查看触发状态。价格为AI基于历史数据推算，仅供参考。")
                                fontSize(11f)
                                color(0xFF888888)
                                lineHeight(16f)
                            }
                        }
                    }
                }
            }
        }
        "risk_card" -> {
            val riskLevel = card["risk_level"] as? String ?: ""
            val risks = (card["risks"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val content = card["content"] as? String ?: ""
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(0xFFFFEBEE)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    Text {
                        attr {
                            text("$title")
                            fontSize(14f)
                            fontWeightBold()
                            color(0xFFD32F2F)
                            flex(1f)
                        }
                    }
                    if (riskLevel.isNotEmpty()) {
                        View {
                            attr {
                                padding(3f, 8f, 3f, 8f)
                                backgroundColor(0xFFD32F2F)
                                borderRadius(10f)
                            }
                            Text {
                                attr {
                                    text(riskLevel)
                                    fontSize(11f)
                                    color(0xFFFFFFFF)
                                    fontWeightBold()
                                }
                            }
                        }
                    }
                }
                if (content.isNotBlank()) {
                    Text {
                        attr {
                            text(content)
                            fontSize(12f)
                            color(0xFF5D4037)
                            marginTop(6f)
                            lineHeight(17f)
                        }
                    }
                }
                risks.forEach { r ->
                    View {
                        attr { flexDirectionRow(); marginTop(6f) }
                        Text { attr { text("•"); fontSize(12f); color(0xFFD32F2F); width(12f) } }
                        Text { attr { text(r); fontSize(12f); color(0xFF5D4037); flex(1f); lineHeight(17f) } }
                    }
                }
            }
        }
        "summary_card" -> {
            val summary = card["summary"] as? String ?: card["content"] as? String ?: ""
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(0xFFF3E5F5)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                }
                Text {
                    attr {
                        text("$title")
                        fontSize(14f)
                        fontWeightBold()
                        color(0xFF7B1FA2)
                    }
                }
                Text {
                    attr {
                        text(summary)
                        fontSize(13f)
                        color(0xFF4A148C)
                        marginTop(6f)
                        lineHeight(19f)
                    }
                }
            }
        }
        else -> {
            val content = card["content"] as? String ?: card.toString()
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(0xFFFFFFFF)
                    borderRadius(10f)
                    padding(12f)
                }
                Text { attr { text(title); fontSize(13f); fontWeightBold(); color(0xFF333333) } }
                Text { attr { text(content); fontSize(12f); color(0xFF666666); marginTop(4f) } }
            }
        }
    }
}

internal fun parseCardPrice(raw: Any?): Double? {
    return when (raw) {
        is Number -> raw.toDouble().takeIf { it.isFinite() && it > 0 }
        is String -> raw.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        else -> null
    }
}

internal fun ViewContainer<*, *>.priceLevelRow(
    ctx: StockDetailPage,
    label: String,
    price: Double,
    currentPrice: Double,
    color: Long,
    alertType: Int
) {
    val dist = if (currentPrice > 0) (price - currentPrice) / currentPrice * 100.0 else 0.0
    val distText = if (currentPrice > 0) fmtSignedPct(dist) else ""
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(6f)
            padding(8f, 10f, 8f, 10f)
            backgroundColor(0xFFF7F9FC)
            borderRadius(8f)
        }
        View {
            attr {
                width(6f)
                height(6f)
                borderRadius(3f)
                backgroundColor(color)
                marginRight(8f)
            }
        }
        Text {
            attr {
                text(label)
                fontSize(12f)
                color(0xFF666666)
                width(52f)
            }
        }
        Text {
            attr {
                text("¥${fmt2(price)}")
                fontSize(13f)
                fontWeightBold()
                color(color)
                flex(1f)
            }
        }
        if (distText.isNotEmpty()) {
            Text {
                attr {
                    text(distText)
                    fontSize(11f)
                    color(if (dist >= 0) StockColors.UP else StockColors.DOWN)
                    marginRight(8f)
                }
            }
        }
        View {
            attr {
                padding(4f, 8f, 4f, 8f)
                backgroundColor(0xFFE8F2FF)
                borderRadius(10f)
                marginRight(4f)
            }
            event { click { ctx.highlightAIPrice(price, label) } }
            Text { attr { text("标注"); fontSize(10f); color(0xFF1976D2); fontWeightBold() } }
        }
        View {
            attr {
                padding(4f, 8f, 4f, 8f)
                backgroundColor(Color(color))
                borderRadius(10f)
            }
            event { click { ctx.prepareAlertFromDetail(alertType, price) } }
            Text { attr { text("设提醒"); fontSize(10f); color(0xFFFFFFFF); fontWeightBold() } }
        }
    }
}

internal fun buildAnalysisMarkdown(a: AIAnalysisData): String {
    val sb = StringBuilder()
    for (card in a.cards) {
        val title = card["title"]?.toString() ?: continue
        when (card["type"] as? String) {
            "trend_card" -> {
                sb.append("**").append(title).append("**\n")
                sb.append(card["content"] ?: "").append("\n\n")
            }
            "signal_card" -> {
                sb.append("**").append(title).append("**\n")
                (card["signals"] as? List<*>)?.forEach { sb.append("• ").append(it).append("\n") }
                sb.append("\n")
            }
            "suggestion_card" -> {
                sb.append("**").append(title).append("**\n")
                sb.append("建议：").append(card["suggestion"] ?: "-").append("\n")
                sb.append("目标价：").append(card["target_price"] ?: "-").append("\n")
                sb.append("止损价：").append(card["stop_loss"] ?: "-").append("\n")
                card["support_price"]?.let {
                    if (it.toString().isNotBlank() && it.toString() != "-") sb.append("支撑位：").append(it).append("\n")
                }
                card["resistance_price"]?.let {
                    if (it.toString().isNotBlank() && it.toString() != "-") sb.append("压力位：").append(it).append("\n")
                }
                sb.append("\n")
            }
            "risk_card" -> {
                sb.append("**").append(title).append("**\n")
                card["risk_level"]?.let { sb.append("风险等级：").append(it).append("\n") }
                (card["risks"] as? List<*>)?.forEach { sb.append("• ").append(it).append("\n") }
                sb.append("\n")
            }
            "summary_card" -> {
                val summary = card["summary"] ?: card["content"] ?: ""
                if (summary.toString().isNotBlank()) {
                    sb.append("**").append(title).append("**\n").append(summary).append("\n\n")
                }
            }
            else -> {
                val content = card["content"] ?: ""
                if (content.toString().isNotBlank()) {
                    sb.append("**").append(title).append("**\n").append(content).append("\n\n")
                }
            }
        }
    }
    if (sb.isEmpty()) return "AI 分析完成，暂无详细内容。"
    return sb.toString().trimEnd()
}
