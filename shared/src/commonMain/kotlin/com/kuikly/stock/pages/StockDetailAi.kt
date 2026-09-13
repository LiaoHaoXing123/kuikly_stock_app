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
import com.kuikly.stock.ui.theme.AppColor

internal data class AIPriceLevel(
    val label: String,
    val price: Double,
    val color: Long,
    val type: String
)

internal fun parseAIPriceLevels(analysis: AIAnalysisData?): List<AIPriceLevel> {
    if (analysis == null) return emptyList()
    val levels = mutableListOf<AIPriceLevel>()
    for (card in analysis.cards) {
        val type = card["type"] as? String ?: continue
        if (type != "suggestion_card" && type != "trend_card") continue

        fun addLevel(key: String, label: String, color: Long, t: String) {
            val raw = card[key] ?: return
            val price = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull() ?: return
                else -> return
            }
            if (price.isFinite() && price > 0) {

                if (levels.none { it.price == price && it.type == t }) {
                    levels.add(AIPriceLevel(label, price, color, t))
                }
            }
        }
        addLevel("support_price", "支撑位", AppColor.DOWN_ALT, "support")
        addLevel("support_value", "支撑位", AppColor.DOWN_ALT, "support")
        addLevel("resistance_price", "压力位", AppColor.UP_ALT, "resistance")
        addLevel("resistance_value", "压力位", AppColor.UP_ALT, "resistance")
        addLevel("target_price", "目标价", AppColor.PRIMARY, "target")
        addLevel("stop_loss", "止损价", AppColor.WARNING_TEXT, "stopLoss")

        addLevel("target", "目标价", AppColor.PRIMARY, "target")
        addLevel("stop", "止损价", AppColor.WARNING_TEXT, "stopLoss")
    }
    return levels.sortedBy { it.price }
}

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
                border(Border(0.8f, BorderStyle.DASHED, Color(AppColor.DIVIDER)))
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
                color(ctx.effectiveVerdict?.colorValue ?: AppColor.TEXT_SUB_DEEP)
            }
        }
    }
}

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

        View {
            attr { height(34f); flexDirectionRow(); alignItemsCenter() }
            when {
                loading -> Text { attr { text("AI 正在研判…"); fontSize(11f); color(Color(AppColor.NEUTRAL)) } }
                v == null -> Text { attr { text("⟡ 点击生成 AI 观点"); fontSize(11f); color(Color(AppColor.ACCENT)); fontWeightBold() } }
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
                        Text { attr { text("参考"); fontSize(9f); color(Color(AppColor.NEUTRAL)); marginLeft(4f) } }
                    }
                    Text {
                        attr {
                            text(v.oneLiner)
                            fontSize(11f)
                            color(Color(AppColor.TEXT_STRONG))
                            marginLeft(6f)
                            flex(1f)
                            lines(1)
                        }
                    }
                    if (!compact) {
                        Text { attr { text(if (expanded) "▲" else "▼"); fontSize(9f); color(Color(AppColor.NEUTRAL)) } }
                    } else {
                        Text { attr { text("详情 ›"); fontSize(10f); color(Color(AppColor.ACCENT)) } }
                    }
                }
            }
        }

        if (!compact && data != null) {
            Text {
                attr {
                    val latest = ctx.stockDetail?.kline?.lastOrNull()?.tradeDate.orEmpty()
                    text(analysisDateLabel(data.dataDate, latest))
                    fontSize(10f)
                    lineHeight(16f)
                    color(AppColor.TEXT_SUB_DEEP)
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
                    pricePill("目标", p, AppColor.WARNING) { ctx.focusKline(KlineFocus.Price(p, "AI目标", AppColor.WARNING)) }
                }
                v.stopLossValue?.let { p ->
                    pricePill("止损", p, AppColor.FLAT) { ctx.focusKline(KlineFocus.Price(p, "AI止损", AppColor.FLAT)) }
                }
            }
            View {
                attr { flexDirectionRow(); alignItemsCenter(); height(26f) }
                Text { attr { text("${v.horizon} · 置信${v.confidence}"); fontSize(10f); color(Color(AppColor.NEUTRAL)); flex(1f) } }
                Text {
                    attr { text("查看完整分析 ›"); fontSize(10f); color(Color(AppColor.ACCENT)) }
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
            backgroundColor(AppColor.SURFACE)
            borderRadius(8f)
            padding(12f)
            marginBottom(10f)
            if (pulsing) border(Border(1.5f, BorderStyle.SOLID, Color(AppColor.ACCENT)))
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginBottom(6f) }
            Text { attr { text(title); fontSize(14f); fontWeightBold(); color(Color(AppColor.TEXT_STRONG)); flex(1f) } }
            Text { attr { text("点击信号定位K线"); fontSize(10f); color(Color(AppColor.NEUTRAL)) } }
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
                        color(if (clickable) Color(AppColor.TEXT_STRONG) else Color(AppColor.TEXT_SUB_DEEP))
                    }
                }
                if (clickable) {
                    Text { attr { text(if (inView) "● 在图中" else "定位 ›"); fontSize(10f); color(Color(AppColor.ACCENT)) } }
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
                    color(AppColor.TEXT_INK)
                    flex(1f)
                }
            }

            vif({ ctx.aiAnalysis != null }) {
                Text {
                    attr {
                        text("${ctx.aiAnalysis!!.cards.size}张卡片")
                        fontSize(11f)
                        color(AppColor.TEXT_HINT)
                        marginRight(8f)
                    }
                }
            }

            vif({ !ctx.isAnalyzing }) {
                View {
                    attr {
                        padding(top = 4f, left = 10f, bottom = 4f, right = 10f)
                        backgroundColor(AppColor.PRIMARY_SOFT)
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
                            color(AppColor.ON_DARK)
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

            vfor({ ObservableList(listOfNotNull(ctx.aiAnalysis).map { Triple(it, ctx.aiExpandedKeys.toList(), ctx.highlightCardType) }.toMutableList()) }) { (analysis, _, _) ->
            View {
                attr { flexDirectionColumn() }
                aiEvidencePanel({ ctx.aiAnalysis }) { ctx.focusEvidenceDate(it) }

                val orderedTypes = listOf("trend_card", "signal_card", "event_card", "suggestion_card", "level_card", "risk_card", "summary_card")
                val grouped = analysis.cards.filter { it["type"] != "evidence_card" }.groupBy { it["type"] as? String ?: "unknown" }
                orderedTypes.forEach { t ->
                    grouped[t]?.forEachIndexed { idx, card ->
                        renderAIAnalysisCard(ctx, card, "${t}_$idx")
                    }
                }

                grouped.filterKeys { it !in orderedTypes }.values.flatten().forEachIndexed { idx, card ->
                    renderAIAnalysisCard(ctx, card, "other_$idx")
                }

                View {
                    attr {
                        flexDirectionColumn()
                        marginTop(10f)
                        padding(10f, 12f, 10f, 12f)
                        backgroundColor(AppColor.PRIMARY_BG_LIGHT)
                        borderRadius(10f)
                    }
                    Text {
                        attr {
                            text("联动交互说明")
                            fontSize(12f)
                            fontWeightBold()
                            color(AppColor.PRIMARY_SOFT)
                        }
                    }
                    Text {
                        attr {
                            text("· 点击AI价位卡片 → K线标注虚线\n· 点击K线 → 查看与AI价位的距离\n· 设提醒 → 写入自选仓，行情刷新时触发\n· 周K/月K → 聚合查看中长期趋势")
                            fontSize(11f)
                            color(AppColor.TEXT_GRAY)
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
        "level_card" -> "关键价位"
        "risk_card" -> "风险提示"
        "event_card" -> "公司事件"
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
                    backgroundColor(AppColor.SURFACE)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1f, BorderStyle.SOLID, Color(AppColor.PRIMARY_BG)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    View {
                        attr {
                            width(4f)
                            height(16f)
                            backgroundColor(AppColor.PRIMARY_SOFT)
                            borderRadius(2f)
                            marginRight(8f)
                        }
                    }
                    Text {
                        attr {
                            text(title)
                            fontSize(14f)
                            fontWeightBold()
                            color(AppColor.PRIMARY_SOFT)
                            flex(1f)
                        }
                    }
                    View {
                        attr {
                            padding(3f, 8f, 3f, 8f)
                            backgroundColor(AppColor.PRIMARY_BG)
                            borderRadius(10f)
                        }
                        event { click { ctx.toggleAISection(key) } }
                        Text {
                            attr {
                                text(if (ctx.isAIExpanded(key)) "收起" else "展开")
                                fontSize(11f)
                                color(AppColor.PRIMARY_SOFT)
                            }
                        }
                    }
                }
                Text {
                    attr {
                        text(content)
                        fontSize(13f)
                        color(AppColor.TEXT_INK)
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
                                backgroundColor(AppColor.SURFACE_SOFT)
                                borderRadius(8f)
                            }
                            Text {
                                attr {
                                    text("倾向：$bias")
                                    fontSize(12f)
                                    color(AppColor.TEXT_GRAY)
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
        "event_card" -> {
            val events = (card["events"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(AppColor.SURFACE)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1f, BorderStyle.SOLID, Color(AppColor.PRIMARY_BG)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    View {
                        attr { width(4f); height(16f); backgroundColor(AppColor.VIOLET); borderRadius(2f); marginRight(8f) }
                    }
                    Text {
                        attr { text(title); fontSize(14f); fontWeightBold(); color(AppColor.PRIMARY_SOFT); flex(1f) }
                    }
                }
                events.forEach { e ->
                    val date = e["date"]?.toString().orEmpty()
                    val kind = e["kind"]?.toString().orEmpty()
                    val label = e["label"]?.toString().orEmpty()
                    View {
                        attr {
                            flexDirectionRow(); alignItems(FlexAlign.CENTER)
                            marginTop(8f); padding(7f, 10f, 7f, 10f)
                            backgroundColor(AppColor.SURFACE_SOFT); borderRadius(8f)
                        }
                        event { click { if (date.isNotEmpty()) ctx.focusEvidenceDate(date) } }
                        vif({ kind.isNotEmpty() }) {
                            View {
                                attr { padding(2f, 7f, 2f, 7f); backgroundColor(AppColor.PRIMARY_BG); borderRadius(6f); marginRight(8f) }
                                Text { attr { text(kind); fontSize(10f); color(AppColor.PRIMARY_SOFT) } }
                            }
                        }
                        Text {
                            attr { text("$date  $label"); fontSize(12f); color(AppColor.TEXT_INK); flex(1f); lineHeight(17f) }
                        }
                    }
                }
                Text {
                    attr { text("点击事件可定位到对应日期的K线"); fontSize(10f); color(AppColor.TEXT_HINT); marginTop(8f) }
                }
            }
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
                    backgroundColor(AppColor.SURFACE)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1.2f, BorderStyle.SOLID, Color(if (ctx.highlightCardType == "level_card") AppColor.ACCENT else AppColor.PRIMARY_SOFT)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    Text {
                        attr {
                            text("$title")
                            fontSize(14f)
                            fontWeightBold()
                            color(AppColor.PRIMARY_SOFT)
                            flex(1f)
                        }
                    }
                    View {
                        attr {
                            padding(4f, 10f, 4f, 10f)
                            backgroundColor(AppColor.PRIMARY_SOFT)
                            borderRadius(12f)
                        }
                        event { click { ctx.toggleAISection(key) } }
                        Text {
                            attr {
                                text(if (ctx.isAIExpanded(key)) "收起详情" else "展开价位")
                                fontSize(11f)
                                color(AppColor.ON_DARK)
                                fontWeightBold()
                            }
                        }
                    }
                }
                Text {
                    attr {
                        text(suggestion)
                        fontSize(13f)
                        color(AppColor.TEXT_INK)
                        marginTop(8f)
                        lineHeight(19f)
                    }
                }

                View {
                    attr { flexDirectionColumn(); marginTop(10f) }
                    if (resistance != null) priceLevelRow(ctx, "压力位", resistance, currentPrice, AppColor.UP_ALT, 0)
                    if (support != null) priceLevelRow(ctx, "支撑位", support, currentPrice, AppColor.DOWN_ALT, 1)
                    if (targetPrice != null) priceLevelRow(ctx, "目标价", targetPrice, currentPrice, AppColor.PRIMARY, 0)
                    if (stopLoss != null) priceLevelRow(ctx, "止损价", stopLoss, currentPrice, AppColor.WARNING_TEXT, 1)
                }

                vif({ ctx.isAIExpanded(key) }) {
                    View {
                        attr {
                            marginTop(10f)
                            padding(10f)
                            backgroundColor(AppColor.SURFACE_SOFT)
                            borderRadius(8f)
                        }
                        Text {
                            attr {
                                text("操作说明：点击价位可在K线标注虚线，设提醒后可在自选仓查看触发状态。价格为AI基于历史数据推算，仅供参考。")
                                fontSize(11f)
                                color(AppColor.TEXT_HINT_SOFT)
                                lineHeight(16f)
                            }
                        }
                    }
                }
            }
        }
        "level_card" -> {

            val action = (card["action"] as? String)?.takeIf { it.isNotBlank() } ?: "观望"
            val target = parseCardPrice(card["target_value"] ?: card["target_price"])
            val stopLoss = parseCardPrice(card["stop_loss_value"] ?: card["stop_loss"])
            val support = parseCardPrice(card["support_value"] ?: card["support_price"])
            val resistance = parseCardPrice(card["resistance_value"] ?: card["resistance_price"])
            val dataDate = card["data_date"] as? String ?: ""
            val currentPrice = ctx.stockDetail?.realtime?.price ?: 0.0
            val actionBg = when (action) {
                "买入" -> AppColor.DOWN_ALT
                "卖出" -> AppColor.UP_ALT
                "持有" -> AppColor.PRIMARY_SOFT
                else -> AppColor.BG_SOFT
            }
            val actionFg = if (action == "观望") AppColor.TEXT_SUB_DEEP else AppColor.ON_DARK

            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(AppColor.SURFACE)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1.2f, BorderStyle.SOLID, Color(if (ctx.highlightCardType == "level_card") AppColor.ACCENT else AppColor.PRIMARY_BG)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    View {
                        attr {
                            width(4f)
                            height(16f)
                            backgroundColor(AppColor.PRIMARY_SOFT)
                            borderRadius(2f)
                            marginRight(8f)
                        }
                    }
                    Text {
                        attr {
                            text(title)
                            fontSize(14f)
                            fontWeightBold()
                            color(AppColor.PRIMARY_SOFT)
                            flex(1f)
                        }
                    }
                    View {
                        attr {
                            padding(4f, 12f, 4f, 12f)
                            backgroundColor(actionBg)
                            borderRadius(12f)
                        }
                        Text {
                            attr {
                                text(action)
                                fontSize(12f)
                                fontWeightBold()
                                color(actionFg)
                            }
                        }
                    }
                }

                View {
                    attr { flexDirectionColumn(); marginTop(10f) }
                    if (resistance != null) priceLevelRow(ctx, "压力位", resistance, currentPrice, AppColor.UP_ALT, 0)
                    if (support != null) priceLevelRow(ctx, "支撑位", support, currentPrice, AppColor.DOWN_ALT, 1)
                    if (target != null) priceLevelRow(ctx, "目标价", target, currentPrice, AppColor.PRIMARY, 0)
                    if (stopLoss != null) priceLevelRow(ctx, "止损价", stopLoss, currentPrice, AppColor.WARNING_TEXT, 1)
                }

                if (dataDate.isNotEmpty()) {
                    Text {
                        attr {
                            text("价位基于 $dataDate 收盘数据推算，点击可标注K线或设提醒")
                            fontSize(10f)
                            color(AppColor.TEXT_HINT)
                            marginTop(8f)
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
                    backgroundColor(AppColor.DANGER_BG)
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
                            color(AppColor.DANGER)
                            flex(1f)
                        }
                    }
                    if (riskLevel.isNotEmpty()) {
                        View {
                            attr {
                                padding(3f, 8f, 3f, 8f)
                                backgroundColor(AppColor.DANGER)
                                borderRadius(10f)
                            }
                            Text {
                                attr {
                                    text(riskLevel)
                                    fontSize(11f)
                                    color(AppColor.ON_DARK)
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
                            color(AppColor.TEXT_WARM)
                            marginTop(6f)
                            lineHeight(17f)
                        }
                    }
                }
                risks.forEach { r ->
                    View {
                        attr { flexDirectionRow(); marginTop(6f) }
                        Text { attr { text("•"); fontSize(12f); color(AppColor.DANGER); width(12f) } }
                        Text { attr { text(r); fontSize(12f); color(AppColor.TEXT_WARM); flex(1f); lineHeight(17f) } }
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
                    backgroundColor(AppColor.VIOLET_BG)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                }
                Text {
                    attr {
                        text("$title")
                        fontSize(14f)
                        fontWeightBold()
                        color(AppColor.VIOLET)
                    }
                }
                Text {
                    attr {
                        text(summary)
                        fontSize(13f)
                        color(AppColor.VIOLET_DEEP)
                        marginTop(6f)
                        lineHeight(19f)
                    }
                }
            }
        }
        else -> {

            val readableText = listOf("content", "summary", "text", "note", "description")
                .firstNotNullOfOrNull { k -> (card[k] as? String)?.takeIf { it.isNotBlank() } }
            val extraFields = card.entries
                .filter { it.key != "type" && it.key != "title" }
                .mapNotNull { (k, v) -> aiCardFieldText(k, v) }
            if (readableText != null || extraFields.isNotEmpty()) {
                View {
                    attr {
                        flexDirectionColumn()
                        marginTop(8f)
                        backgroundColor(AppColor.SURFACE)
                        borderRadius(10f)
                        padding(12f)
                    }
                    Text { attr { text(title); fontSize(13f); fontWeightBold(); color(AppColor.TEXT_INK) } }
                    if (readableText != null) {
                        Text {
                            attr {
                                text(readableText)
                                fontSize(12f)
                                color(AppColor.TEXT_GRAY)
                                marginTop(4f)
                                lineHeight(18f)
                            }
                        }
                    }
                    extraFields.forEach { (label, value) ->
                        View {
                            attr { flexDirectionRow(); marginTop(4f) }
                            Text { attr { text(label); fontSize(12f); color(AppColor.TEXT_HINT_SOFT); width(72f) } }
                            Text { attr { text(value); fontSize(12f); color(AppColor.TEXT_INK); flex(1f); lineHeight(18f) } }
                        }
                    }
                }
            }
        }
    }
}

private val AI_CARD_FIELD_LABELS = mapOf(
    "action" to "操作倾向",
    "target_value" to "目标价",
    "target_price" to "目标价",
    "stop_loss_value" to "止损价",
    "stop_loss" to "止损价",
    "support_value" to "支撑位",
    "support_price" to "支撑位",
    "resistance_value" to "压力位",
    "resistance_price" to "压力位",
    "data_date" to "数据日期",
    "indicator_date" to "指标日期",
    "bias" to "倾向",
    "confidence" to "置信度",
    "horizon" to "周期",
    "risk_level" to "风险等级",
    "ref_price" to "参考价",
    "start_date" to "开始日期",
    "end_date" to "结束日期",
)

internal fun aiCardFieldText(key: String, value: Any?): Pair<String, String>? {
    val label = AI_CARD_FIELD_LABELS[key] ?: return null
    val text = when (value) {
        null -> return null
        is String -> value.takeIf { it.isNotBlank() } ?: return null
        is Number -> value.toString()
        is Boolean -> if (value) "是" else "否"
        is List<*> -> value.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            .joinToString("、").takeIf { it.isNotEmpty() } ?: return null
        else -> return null
    }
    return label to text
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
            backgroundColor(AppColor.SURFACE_TINT)
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
                color(AppColor.TEXT_GRAY)
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
                backgroundColor(AppColor.INFO_BG)
                borderRadius(10f)
                marginRight(4f)
            }
            event { click { ctx.highlightAIPrice(price, label) } }
            Text { attr { text("标注"); fontSize(10f); color(AppColor.PRIMARY_SOFT); fontWeightBold() } }
        }
        View {
            attr {
                padding(4f, 8f, 4f, 8f)
                backgroundColor(Color(color))
                borderRadius(10f)
            }
            event { click { ctx.prepareAlertFromDetail(alertType, price) } }
            Text { attr { text("设提醒"); fontSize(10f); color(AppColor.ON_DARK); fontWeightBold() } }
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
