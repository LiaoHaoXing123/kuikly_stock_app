package com.kuikly.stock.pages

import com.kuikly.stock.data.StockColors

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.AccessibilityRole
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
import com.kuikly.stock.base.BasePager
import com.kuikly.stock.base.HapticStyle
import com.kuikly.stock.base.hapticTick
import com.kuikly.stock.ui.component.skeletonBlock
import com.kuikly.stock.ui.component.segmentedControl
import kotlin.math.abs
import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.component.chartControlButton

internal fun ViewContainer<*, *>.klineChartArea(ctx: StockDetailPage) {

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 10f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(8f) }
            Text {
                attr {
                    text("K线走势")
                    fontSize(15f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                    flex(1f)
                }
            }
            Text {
                attr {
                    text(ctx.getAggregatedKline().getOrNull(ctx.selectedKlineIndex)?.tradeDate ?: "显示 ${ctx.klineVisibleCount} / ${ctx.getAggregatedKline().size} 根")
                    fontSize(10f)
                    color(AppColor.TEXT_HINT)
                    flex(1f)
                    textAlignRight()
                }
            }
        }

        View {
            attr { flexDirectionRow(); marginBottom(8f) }
            klinePeriodBar(ctx)
            View { attr { flex(1f) } }

            View {
                attr { height(34f); paddingLeft(8f); paddingRight(8f); allCenter(); backgroundColor(AppColor.PRIMARY_BG); borderRadius(8f); marginRight(6f); accessibility("重置K线视口") }
                event { click { ctx.resetKline() } }
                Text { attr { text("重置"); fontSize(11f); color(AppColor.PRIMARY) } }
            }
            vif({ ctx.matureChartAvailable }) {
                View {
                    attr { height(34f); paddingLeft(8f); paddingRight(8f); allCenter(); backgroundColor(AppColor.PRIMARY_BG); borderRadius(8f); marginRight(6f) }
                    event { click { ctx.matureChartEnabled = !ctx.matureChartEnabled } }
                    Text { attr { text(if (ctx.matureChartEnabled) "专业图" else "基础图"); fontSize(11f); color(AppColor.PRIMARY) } }
                }
            }
            vif({ !ctx.matureChartAvailable || !ctx.matureChartEnabled }) {
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(if (ctx.klineShowMA) AppColor.PRIMARY_BG else AppColor.SURFACE_SOFT)
                    borderRadius(10f)
                    marginRight(6f)
                }
                event { click { ctx.toggleMA() } }
                Text {
                    attr {
                        text("MA")
                        fontSize(11f)
                        color(if (ctx.klineShowMA) AppColor.PRIMARY_SOFT else AppColor.TEXT_HINT)
                        fontWeightBold()
                    }
                }
            }
            View {
                attr { minWidth(44f); height(34f); allCenter(); backgroundColor(AppColor.SURFACE_ALT); borderRadius(10f); accessibility("展开或收起图表工具") }
                event { click { ctx.klineToolsExpanded = !ctx.klineToolsExpanded } }
                Text { attr { text(if (ctx.klineToolsExpanded) "收起" else "工具"); fontSize(11f); color(AppColor.PRIMARY) } }
            }
            }
        }

        vif({ ctx.isLoading }) {
            klineLoadingView(ctx)
        }
        velseif({ !ctx.stockDetail?.kline.isNullOrEmpty() }) {
            vif({ !ctx.matureChartAvailable || !ctx.matureChartEnabled }) {
            vif({ ctx.klineToolsExpanded }) {

            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
                chartControlButton("◀◀", { ctx.panLeft() })
                chartControlButton("－", { ctx.zoomIn() })
                Text {
                    attr {
                        text("${ctx.klineVisibleCount}根")
                        fontSize(11f)
                        color(AppColor.TEXT_GRAY)
                        marginLeft(6f)
                        marginRight(6f)
                        width(40f)
                        textAlignCenter()
                    }
                }
                chartControlButton("＋", { ctx.zoomOut() })
                chartControlButton("▶▶", { ctx.panRight() })
                chartControlButton("重置", { ctx.resetKline() })
                View { attr { flex(1f) } }
                vif({ ctx.highlightedPrice > 0 }) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItems(FlexAlign.CENTER)
                            backgroundColor(AppColor.WARNING_BG)
                            borderRadius(8f)
                            padding(3f, 8f, 3f, 8f)
                            marginBottom(8f)
                        }
                        Text {
                            attr {
                                text("${ctx.highlightedPriceLabel} ¥${fmt2(ctx.highlightedPrice)}")
                                fontSize(11f)
                                color(AppColor.WARNING_TEXT)
                                flex(1f)
                            }
                        }
                        View {
                            attr { marginLeft(6f); padding(2f, 6f, 2f, 6f); backgroundColor(AppColor.SURFACE); borderRadius(6f) }
                            event { click { ctx.clearHighlight() } }
                            Text { attr { text("✕"); fontSize(10f); color(AppColor.TEXT_HINT) } }
                        }
                    }
                }
            }
            }

            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
                Text { attr { text("副图"); fontSize(11f); color(AppColor.TEXT_HINT); marginRight(8f) } }
                subIndicatorChip(ctx, "none", "关")
                subIndicatorChip(ctx, "macd", "MACD")
                subIndicatorChip(ctx, "kdj", "KDJ")
                subIndicatorChip(ctx, "rsi", "RSI")
                View { attr { flex(1f) } }
                View {
                    attr { padding(6f); borderRadius(8f); backgroundColor(if (ctx.klineShowTrend) AppColor.PRIMARY_BG else AppColor.SURFACE_ALT) }
                    event { click { ctx.toggleTrend() } }
                    Text { attr { text(if (ctx.klineShowTrend) "趋势 开" else "趋势 关"); fontSize(11f); color(if (ctx.klineShowTrend) AppColor.PRIMARY_SOFT else AppColor.TEXT_SUB_DEEP) } }
                }
                View {
                    attr { marginLeft(6f); padding(6f); borderRadius(8f); backgroundColor(AppColor.SURFACE_ALT) }
                    event { click { ctx.toggleVolume() } }
                    Text { attr { text(if (ctx.klineShowVolume) "量 开" else "量 关"); fontSize(11f); color(AppColor.TEXT_SUB_DEEP) } }
                }
            }
            vif({ ctx.klineToolsExpanded }) {
                Text { attr { text("横拖平移 · 点选锁定 · 长按选区间 · 双指缩放"); fontSize(10f); lineHeight(16f); color(AppColor.TEXT_SUB); marginBottom(6f) } }
            }
            }

            View {
                attr { flexDirectionColumn() }
                vif({ ctx.rangeStats != null || ctx.selectedKlineIndex >= 0 }) {
                    View {
                        attr {
                            flexDirectionRow(); alignItems(FlexAlign.CENTER)
                            backgroundColor(AppColor.SURFACE_ALT); borderRadius(8f)
                            padding(4f, 8f, 4f, 8f); marginBottom(6f)
                        }
                        Text {
                            attr {
                                text(if (ctx.rangeStats != null) "已框选区间 · 查看统计" else "已锁定单根 K 线")
                                fontSize(11f); color(AppColor.TEXT_SUB_DEEP); flex(1f)
                            }
                        }
                        View {
                            attr { padding(2f, 10f, 2f, 10f); backgroundColor(AppColor.SURFACE); borderRadius(6f) }
                            event { click { ctx.clearInteraction() } }
                            Text { attr { text("✕ 退出"); fontSize(11f); color(AppColor.PRIMARY_SOFT) } }
                        }
                    }
                }
                View {
                    vif({ ctx.matureChartAvailable && ctx.matureChartEnabled }) {
                        matureKlineChart(ctx)
                    }
                    velse {
                        View {
                            attr { flexDirectionRow() }

                            View {
                                attr { width(30f); flexDirectionColumn(); justifyContentCenter() }
                                zoomFab("＋") { ctx.zoomIn() }
                                zoomFab("－") { ctx.zoomOut() }
                            }
                            View { attr { flex(1f) }
                                klineChartCanvas(ctx)
                                chartTouchLayer(ctx)
                            }
                        }
                    }
                }
                vfor({ ObservableList(mutableListOf(listOf(ctx.klineStartIndex, ctx.klineVisibleCount, ctx.selectedKlineIndex, ctx.klinePeriod))) }) { _ ->
                klineSummary(ctx, ctx.getVisibleKline())
                }
            }
                vfor({ ObservableList(mutableListOf(listOf(ctx.aiAnalysis, ctx.verdictExpanded, ctx.isAnalyzing))) }) { _ ->
                aiVerdictBar(ctx)
                }
            chartEvidencePanel({ ctx.getAggregatedKline() }, { ctx.selectedKlineIndex }, { ctx.aiAnalysis }, { ctx.focusCandle(it) }, { ctx.askAboutChart() })
            vif({ ctx.selectedKlineIndex >= 0 && ctx.klinePeriod == "D" }) {
                Text {
                    attr {
                        text(fundEvidence(ctx.stockDetail?.fundFlow.orEmpty(), ctx.getAggregatedKline().getOrNull(ctx.selectedKlineIndex)?.tradeDate.orEmpty()))
                        fontSize(11f); lineHeight(18f); color(AppColor.TEXT_SUB_DEEP); marginTop(8f)
                    }
                }
            }

            vfor({ ObservableList(listOfNotNull(ctx.aiAnalysis).toMutableList()) }) { analysis ->
                View {
                    attr { flexDirectionRow(); flexWrapWrap(); marginTop(8f) }
                    val levels = parseAIPriceLevels(analysis)
                    levels.forEach { lvl ->
                        View {
                            attr {
                                flexDirectionRow()
                                alignItems(FlexAlign.CENTER)
                                marginRight(8f)
                                marginBottom(4f)
                                padding(3f, 8f, 3f, 8f)
                                backgroundColor(
                                    when (lvl.type) {
                                        "support" -> AppColor.SUCCESS_BG
                                        "resistance" -> AppColor.DANGER_BG
                                        "target" -> AppColor.INFO_BG
                                        else -> AppColor.WARNING_BG
                                    }
                                )
                                borderRadius(10f)
                            }
                            event { click { ctx.highlightAIPrice(lvl.price, lvl.label) } }
                            View {
                                attr {
                                    width(8f)
                                    height(8f)
                                    borderRadius(4f)
                                    backgroundColor(lvl.color)
                                    marginRight(4f)
                                }
                            }
                            Text {
                                attr {
                                    text("${lvl.label} ${fmt2(lvl.price)}")
                                    fontSize(10f)
                                    color(lvl.color)
                                    fontWeightBold()
                                }
                            }
                        }
                    }
                }
            }

            Text {
                attr {
                    text("提示：点击/长按K线查看详情 · 缩放平移查看历史 · 点击下方AI价位联动标注")
                    fontSize(10f)
                    color(AppColor.DISABLED)
                    marginTop(6f)
                }
            }
        }
        velse {
            klineErrorView(ctx)
        }
    }
}

private const val KLINE_PERIOD_TAB_W = 46f

private val KLINE_PERIODS = listOf("D" to "日K", "W" to "周K", "M" to "月K")

internal fun ViewContainer<*, *>.klinePeriodBar(ctx: StockDetailPage) {
    segmentedControl(
        options = KLINE_PERIODS.map { it.second },
        selectedIndex = { KLINE_PERIODS.indexOfFirst { it.first == ctx.klinePeriod } },
        itemWidth = KLINE_PERIOD_TAB_W,
        onSelect = { ctx.switchKlinePeriod(KLINE_PERIODS[it].first) },
    )
}

internal fun ViewContainer<*, *>.subIndicatorChip(ctx: StockDetailPage, key: String, label: String) {
    View {
        attr {
            padding(3f, 10f, 3f, 10f)
            backgroundColor(if (ctx.klineSubIndicator == key) AppColor.ACCENT else AppColor.SURFACE_SOFT)
            borderRadius(12f)
            marginRight(6f)
        }
        event { click { hapticTick(HapticStyle.Light); ctx.klineSubIndicator = key } }
        Text {
            attr {
                text(label)
                fontSize(11f)
                fontWeightBold()
                color(if (ctx.klineSubIndicator == key) AppColor.ON_DARK else AppColor.TEXT_HINT_SOFT)
            }
        }
    }
}

internal fun ViewContainer<*, *>.klineLoadingView(ctx: BasePager) {
    val sweep = ctx.skeletonPulse
    View {
        attr {
            flexDirectionColumn()
        }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
            skeletonBlock(height = 22f, w = 40f, radius = 11f, sweep = sweep)
            View { attr { width(6f) } }
            skeletonBlock(height = 22f, w = 40f, radius = 11f, sweep = sweep)
            View { attr { width(6f) } }
            skeletonBlock(height = 22f, w = 40f, radius = 11f, sweep = sweep)
            View { attr { flex(1f) } }
            skeletonBlock(height = 22f, w = 34f, radius = 11f, sweep = sweep)
        }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
            repeat(4) {
                skeletonBlock(height = 28f, w = 32f, radius = 8f, sweep = sweep)
                View { attr { width(4f) } }
            }
            View { attr { flex(1f) } }
        }

        skeletonBlock(height = 200f, radius = 8f, sweep = sweep)
    }
}

internal fun ViewContainer<*, *>.klineErrorView(ctx: StockDetailPage) {
    View {
        attr {
            padding(top = 24f, left = 0f, bottom = 24f, right = 0f)
            alignItems(FlexAlign.CENTER)
            flexDirectionColumn()
        }
        Text {
            attr {
                text("K线数据获取失败或暂无数据")
                fontSize(13f)
                color(AppColor.TEXT_HINT)
                textAlignCenter()
            }
        }
        View {
            attr {
                marginTop(12f)
                padding(top = 8f, left = 20f, bottom = 8f, right = 20f)
                backgroundColor(AppColor.PRIMARY_BG)
                borderRadius(16f)
            }
            event {
                click { ctx.loadStockDetail() }
            }
            Text {
                attr {
                    text("重试")
                    fontSize(13f)
                    fontWeightBold()
                    color(AppColor.PRIMARY_SOFT)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.zoomFab(label: String, action: () -> Unit) {
    View {
        attr {
            size(30f, 44f)
            allCenter()
            margin(3f)
            borderRadius(9f)
            backgroundColor(AppColor.SURFACE_ALT)
            accessibility("缩放${if (label == "＋") "放大" else "缩小"}")
            accessibilityRole(AccessibilityRole.BUTTON)
        }
        event { click { action() } }
        Text { attr { text(label); fontSize(15f); fontWeightBold(); color(AppColor.PRIMARY_SOFT) } }
    }
}

internal fun ViewContainer<*, *>.klineChartCanvas(ctx: StockDetailPage) {
    Canvas({
        attr {
            height((if (ctx.klineShowVolume) 380f else 300f) + (if (ctx.klineSubIndicator != "none") 86f else 0f))
            marginTop(2f)
            backgroundColor(AppColor.SURFACE)
        }
    }) { context, width, height ->
        val aggregated = ctx.getAggregatedKline()
        val viewport = klineViewport(aggregated.size, ctx.klineStartIndex, ctx.klineVisibleCount)
        if (viewport.isEmpty()) return@Canvas
        val visibleStart = viewport.first
        val visible = aggregated.subList(visibleStart, viewport.last + 1)
        val aiLevels = parseAIPriceLevels(ctx.aiAnalysis)
        val nTotal = aggregated.size
        val nVisible = visible.size
        if (nTotal == 0 || nVisible == 0 || width <= 0f || height <= 0f) return@Canvas

        if (ctx.klineCanvasWidth != width) {
            ctx.klineCanvasWidth = width
        }

        val tooltipH = 36f
        val padT = 38f

        val subOn = ctx.klineSubIndicator != "none"
        val subReserve = if (subOn) 86f else 0f
        val volTop = (if (ctx.klineShowVolume) height - 70f else height - 20f) - subReserve
        val volH = if (ctx.klineShowVolume) 44f else 0f
        val dateY = height - 10f
        val subTop = volTop + volH + 10f
        val subBottom = subTop + 76f

        val priceList = visible.flatMap { listOf(it.high, it.low, it.open, it.close) }.toMutableList()
        aiLevels.forEach { if (it.price > 0) priceList.add(it.price) }
        if (ctx.highlightedPrice > 0) priceList.add(ctx.highlightedPrice)
        var minP = priceList.minOrNull() ?: 0.0
        var maxP = priceList.maxOrNull() ?: 1.0
        if (maxP <= minP) maxP = minP + 1.0

        val pad = (maxP - minP) * 0.08
        minP -= pad
        maxP += pad

        val chartH = volTop - padT - 6f
        fun py(p: Double): Float = padT + chartH * ((maxP - p) / (maxP - minP)).toFloat()

        val step = width / nVisible.coerceAtLeast(1)
        val cw = (step * 0.55f).coerceAtLeast(2f).coerceAtMost(14f)

        context.strokeStyle(Color(AppColor.BG_SOFT))
        context.lineWidth(1f)
        for (i in 0..4) {
            val gy = padT + chartH * i / 4f
            context.beginPath()
            context.moveTo(0f, gy)
            context.lineTo(width, gy)
            context.stroke()
        }

        context.strokeStyle(Color(AppColor.SURFACE_ALT))
        for (i in 0..nVisible step (nVisible / 5).coerceAtLeast(1)) {
            val cx = step * i + step / 2f
            context.beginPath()
            context.moveTo(cx, padT)
            context.lineTo(cx, volTop)
            context.stroke()
        }

        aiLevels.forEach { lvl ->
            if (lvl.price in minP..maxP) {
                val y = py(lvl.price)

                context.strokeStyle(Color(lvl.color))
                context.lineWidth(1f)
                var x = 0f
                while (x < width) {
                    context.beginPath()
                    context.moveTo(x, y)
                    context.lineTo((x + 6f).coerceAtMost(width), y)
                    context.stroke()
                    x += 10f
                }

                context.fillStyle(Color(lvl.color))
                context.font(9f)
                context.textAlign(TextAlign.RIGHT)
                context.fillText("${lvl.label} ${fmt2(lvl.price)}", width - 2f, y - 3f)
            }
        }

        if (ctx.highlightedPrice > 0 && ctx.highlightedPrice in minP..maxP) {
            val y = py(ctx.highlightedPrice)
            context.strokeStyle(Color(AppColor.WARNING))
            context.lineWidth(2f)
            context.beginPath()
            context.moveTo(0f, y)
            context.lineTo(width, y)
            context.stroke()
            context.fillStyle(Color(AppColor.WARNING))
            context.font(10f)
            context.textAlign(TextAlign.LEFT)
            context.fillText("★ ${ctx.highlightedPriceLabel} ${fmt2(ctx.highlightedPrice)}", 4f, y - 4f)
        }

        val closes = aggregated.map { it.close }
        fun maAt(index: Int, period: Int): Double? {
            if (index !in closes.indices || index < period - 1) return null
            return closes.subList(index - period + 1, index + 1).average()
        }

        val ma5Points = mutableListOf<Pair<Float, Float>>()
        val ma10Points = mutableListOf<Pair<Float, Float>>()
        val ma20Points = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until nVisible) {
            val globalIdx = visibleStart + i
            val cx = step * i + step / 2f
            maAt(globalIdx, 5)?.let { ma5Points.add(cx to py(it)) }
            maAt(globalIdx, 10)?.let { ma10Points.add(cx to py(it)) }
            maAt(globalIdx, 20)?.let { ma20Points.add(cx to py(it)) }
        }

        visible.forEachIndexed { i, k ->
            val cx = step * i + step / 2f
            val up = k.close >= k.open
            val color = if (up) Color(StockColors.UP) else Color(StockColors.DOWN)

            context.strokeStyle(color)
            context.lineWidth(1f)
            context.beginPath()
            context.moveTo(cx, py(k.high))
            context.lineTo(cx, py(k.low))
            context.stroke()

            val yo = py(k.open)
            val yc = py(k.close)
            val top = minOf(yo, yc)
            val bh = abs(yo - yc).coerceAtLeast(1.5f)
            context.fillStyle(color)
            context.beginPath()
            context.moveTo(cx - cw / 2f, top)
            context.lineTo(cx + cw / 2f, top)
            context.lineTo(cx + cw / 2f, top + bh)
            context.lineTo(cx - cw / 2f, top + bh)
            context.closePath()
            context.fill()
        }

        if (ctx.klineShowMA) {
            fun drawMALine(points: List<Pair<Float, Float>>, color: Color) {
                if (points.size < 2) return
                context.strokeStyle(color)
                context.lineWidth(1.2f)
                context.beginPath()
                points.forEachIndexed { idx, (x, y) ->
                    if (idx == 0) context.moveTo(x, y) else context.lineTo(x, y)
                }
                context.stroke()
            }
            drawMALine(ma5Points, Color(AppColor.PRIMARY_SOFT))
            drawMALine(ma10Points, Color(AppColor.WARNING))
            drawMALine(ma20Points, Color(0xFF7B1FA2))
        }

        if (ctx.klineShowTrend && nVisible >= 5) {
            val trend = computeTrendlines(visible.map { it.high }, visible.map { it.low })
            val chartBottom = padT + chartH
            fun clampY(y: Float): Float = y.coerceIn(padT, chartBottom)
            fun drawTrend(line: TrendLine?, colorValue: Long) {
                line ?: return
                val x1 = step * line.x1 + step / 2f
                val lastX = step * (nVisible - 1) + step / 2f
                val yA = clampY(py(line.y1))
                val yB = clampY(py(line.valueAt(nVisible - 1)))
                context.strokeStyle(Color(colorValue)); context.lineWidth(1.2f)
                context.beginPath(); context.moveTo(x1, yA); context.lineTo(lastX, yB); context.stroke()
            }
            drawTrend(trend.resistance, 0xFFEF5350)
            drawTrend(trend.support, 0xFF26A69A)
        }

        if (ctx.klineShowVolume && volH > 0) {
            val maxVol = visible.maxOf { it.volume }.toFloat().coerceAtLeast(1f)
            context.strokeStyle(Color(AppColor.DIVIDER))
            context.lineWidth(1f)
            context.beginPath()
            context.moveTo(0f, volTop - 5f)
            context.lineTo(width, volTop - 5f)
            context.stroke()
            visible.forEachIndexed { i, k ->
                val cx = step * i + step / 2f
                val up = k.close >= k.open
                val color = if (up) Color(StockColors.UP) else Color(StockColors.DOWN)
                val vh = (volH * (k.volume.toFloat() / maxVol)).coerceAtLeast(1.2f)
                context.fillStyle(color)
                context.beginPath()
                context.moveTo(cx - cw / 2f, volTop + (volH - vh))
                context.lineTo(cx + cw / 2f, volTop + (volH - vh))
                context.lineTo(cx + cw / 2f, volTop + volH)
                context.lineTo(cx - cw / 2f, volTop + volH)
                context.closePath()
                context.fill()
            }
            context.fillStyle(Color(AppColor.TEXT_HINT))
            context.font(9f)
            context.textAlign(TextAlign.RIGHT)
            context.fillText("成交量", width - 2f, volTop - 8f)
        }

        if (subOn) {
            val subH = subBottom - subTop

            context.strokeStyle(Color(AppColor.DIVIDER))
            context.lineWidth(1f)
            context.beginPath()
            context.moveTo(0f, subTop - 5f)
            context.lineTo(width, subTop - 5f)
            context.stroke()

            val subActiveIdx = ctx.crosshair.activeIndex ?: -1
            val subActiveLocal = (subActiveIdx - visibleStart).let { if (it in 0 until nVisible) it else nVisible - 1 }

            if (ctx.klineSubIndicator == "macd") {
                val macd = ctx.macdOf(aggregated)
                val difV = ArrayList<Double>(nVisible)
                val deaV = ArrayList<Double>(nVisible)
                val histV = ArrayList<Double>(nVisible)
                for (i in 0 until nVisible) {
                    val gi = visibleStart + i
                    difV.add(macd.dif.getOrElse(gi) { 0.0 })
                    deaV.add(macd.dea.getOrElse(gi) { 0.0 })
                    histV.add(macd.hist.getOrElse(gi) { 0.0 })
                }
                var lo = 0.0
                var hi = 0.0
                for (i in 0 until nVisible) {
                    lo = minOf(lo, difV[i], deaV[i], histV[i])
                    hi = maxOf(hi, difV[i], deaV[i], histV[i])
                }
                if (hi <= lo) hi = lo + 1.0
                fun syToY(v: Double): Float = subTop + subH * ((hi - v) / (hi - lo)).toFloat()
                val zeroY = syToY(0.0)

                context.strokeStyle(Color(AppColor.DIVIDER))
                context.lineWidth(1f)
                context.beginPath(); context.moveTo(0f, zeroY); context.lineTo(width, zeroY); context.stroke()

                for (i in 0 until nVisible) {
                    val cx = step * i + step / 2f
                    val h = histV[i]
                    val y = syToY(h)
                    context.fillStyle(Color(if (h >= 0.0) StockColors.UP else StockColors.DOWN))
                    val topY = minOf(y, zeroY)
                    val botY = maxOf(y, zeroY).coerceAtLeast(topY + 0.8f)
                    context.beginPath()
                    context.moveTo(cx - cw / 2f, topY)
                    context.lineTo(cx + cw / 2f, topY)
                    context.lineTo(cx + cw / 2f, botY)
                    context.lineTo(cx - cw / 2f, botY)
                    context.closePath(); context.fill()
                }
                fun drawSubLine(vals: List<Double>, colorValue: Long) {
                    context.strokeStyle(Color(colorValue)); context.lineWidth(1.2f); context.beginPath()
                    for (i in 0 until nVisible) {
                        val cx = step * i + step / 2f
                        val y = syToY(vals[i])
                        if (i == 0) context.moveTo(cx, y) else context.lineTo(cx, y)
                    }
                    context.stroke()
                }
                drawSubLine(difV, AppColor.PRIMARY_SOFT)
                drawSubLine(deaV, AppColor.WARNING)
                context.fillStyle(Color(AppColor.TEXT_HINT_SOFT)); context.font(9f); context.textAlign(TextAlign.LEFT)
                val si = subActiveLocal.coerceIn(0, nVisible - 1)
                context.fillText("MACD(12,26,9)  DIF ${fmt2(difV[si])}  DEA ${fmt2(deaV[si])}  M ${fmt2(histV[si])}", 4f, subTop + 9f)
            } else if (ctx.klineSubIndicator == "rsi") {
                val rsi = ctx.rsiOf(aggregated)
                val r6 = ArrayList<Double>(nVisible)
                val r12 = ArrayList<Double>(nVisible)
                val r24 = ArrayList<Double>(nVisible)
                for (i in 0 until nVisible) {
                    val gi = visibleStart + i
                    r6.add(rsi.rsi6.getOrElse(gi) { 50.0 })
                    r12.add(rsi.rsi12.getOrElse(gi) { 50.0 })
                    r24.add(rsi.rsi24.getOrElse(gi) { 50.0 })
                }

                val lo = 0.0
                val hi = 100.0
                fun syToY(v: Double): Float = subTop + subH * ((hi - v) / (hi - lo)).toFloat()

                context.strokeStyle(Color(AppColor.DIVIDER_SOFT)); context.lineWidth(1f)
                for (ref in listOf(30.0, 70.0)) {
                    val ry = syToY(ref)
                    var rx = 0f
                    while (rx < width) {
                        context.beginPath(); context.moveTo(rx, ry); context.lineTo((rx + 6f).coerceAtMost(width), ry); context.stroke()
                        rx += 10f
                    }
                }
                fun drawSubLine(vals: List<Double>, colorValue: Long) {
                    context.strokeStyle(Color(colorValue)); context.lineWidth(1.2f); context.beginPath()
                    for (i in 0 until nVisible) {
                        val cx = step * i + step / 2f
                        val y = syToY(vals[i])
                        if (i == 0) context.moveTo(cx, y) else context.lineTo(cx, y)
                    }
                    context.stroke()
                }
                drawSubLine(r6, AppColor.PRIMARY_SOFT)
                drawSubLine(r12, AppColor.WARNING)
                drawSubLine(r24, 0xFF7B1FA2)
                context.fillStyle(Color(AppColor.TEXT_HINT_SOFT)); context.font(9f); context.textAlign(TextAlign.LEFT)
                val si = subActiveLocal.coerceIn(0, nVisible - 1)
                context.fillText("RSI(6,12,24)  RSI6 ${fmt2(r6[si])}  RSI12 ${fmt2(r12[si])}  RSI24 ${fmt2(r24[si])}", 4f, subTop + 9f)
            } else {
                val kdj = ctx.kdjOf(aggregated)
                val kV = ArrayList<Double>(nVisible)
                val dV = ArrayList<Double>(nVisible)
                val jV = ArrayList<Double>(nVisible)
                for (i in 0 until nVisible) {
                    val gi = visibleStart + i
                    kV.add(kdj.k.getOrElse(gi) { 50.0 })
                    dV.add(kdj.d.getOrElse(gi) { 50.0 })
                    jV.add(kdj.j.getOrElse(gi) { 50.0 })
                }
                var lo = 0.0
                var hi = 100.0
                for (i in 0 until nVisible) {
                    lo = minOf(lo, kV[i], dV[i], jV[i])
                    hi = maxOf(hi, kV[i], dV[i], jV[i])
                }
                if (hi <= lo) hi = lo + 1.0
                fun syToY(v: Double): Float = subTop + subH * ((hi - v) / (hi - lo)).toFloat()

                context.strokeStyle(Color(AppColor.DIVIDER_SOFT)); context.lineWidth(1f)
                for (ref in listOf(20.0, 80.0)) {
                    val ry = syToY(ref)
                    var rx = 0f
                    while (rx < width) {
                        context.beginPath(); context.moveTo(rx, ry); context.lineTo((rx + 6f).coerceAtMost(width), ry); context.stroke()
                        rx += 10f
                    }
                }
                fun drawSubLine(vals: List<Double>, colorValue: Long) {
                    context.strokeStyle(Color(colorValue)); context.lineWidth(1.2f); context.beginPath()
                    for (i in 0 until nVisible) {
                        val cx = step * i + step / 2f
                        val y = syToY(vals[i])
                        if (i == 0) context.moveTo(cx, y) else context.lineTo(cx, y)
                    }
                    context.stroke()
                }
                drawSubLine(kV, AppColor.PRIMARY_SOFT)
                drawSubLine(dV, AppColor.WARNING)
                drawSubLine(jV, 0xFF7B1FA2)
                context.fillStyle(Color(AppColor.TEXT_HINT_SOFT)); context.font(9f); context.textAlign(TextAlign.LEFT)
                val si = subActiveLocal.coerceIn(0, nVisible - 1)
                context.fillText("KDJ(9,3,3)  K ${fmt2(kV[si])}  D ${fmt2(dV[si])}  J ${fmt2(jV[si])}", 4f, subTop + 9f)
            }
        }

        context.fillStyle(Color(AppColor.TEXT_HINT))
        context.font(10f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(maxP), 4f, padT + 9f)
        context.fillText(fmt2(minP), 4f, volTop - 6f)

        if (visible.isNotEmpty()) {
            val firstDate = visible.first().tradeDate
            val lastDate = visible.last().tradeDate
            context.font(9f)
            context.textAlign(TextAlign.LEFT)
            context.fillText(firstDate, 2f, dateY)
            context.textAlign(TextAlign.RIGHT)
            context.fillText(lastDate, width - 2f, dateY)

            if (nVisible > 10) {
                context.textAlign(TextAlign.CENTER)
                context.fillText(visible[nVisible / 2].tradeDate, width / 2f, dateY)
            }
        }

        val interaction = ctx.crosshair.state
        val showCrosshair = ctx.crosshairX >= 0f && (interaction is InteractionState.Hover || interaction is InteractionState.Locked || interaction is InteractionState.RangeSelect)

        if (showCrosshair) {
            val cx = ctx.crosshairX.coerceIn(0f, width)
            val activeIdx = ctx.crosshair.activeIndex ?: -1
            val localIdx = activeIdx - visibleStart
            val k = visible.getOrNull(localIdx)

            context.strokeStyle(Color(AppColor.TEXT_GRAY))
            context.lineWidth(1f)
            val vLineBottom = if (subOn) subBottom else volTop
            var vx = padT
            while (vx < vLineBottom) {
                context.beginPath()
                context.moveTo(cx, vx)
                context.lineTo(cx, (vx + 4f).coerceAtMost(vLineBottom))
                context.stroke()
                vx += 8f
            }

            val hy = if (ctx.crosshairY in padT..volTop) ctx.crosshairY else (k?.let { py(it.close) } ?: (padT + chartH / 2))
            var hx = 0f
            while (hx < width) {
                context.beginPath()
                context.moveTo(hx, hy)
                context.lineTo((hx + 4f).coerceAtMost(width), hy)
                context.stroke()
                hx += 8f
            }

            val priceAtY = if (ctx.crosshairY in padT..volTop) {
                val ratio = 1f - ((ctx.crosshairY - padT) / chartH).coerceIn(0f, 1f)
                minP + ratio * (maxP - minP)
            } else {
                k?.close ?: 0.0
            }
            val priceTagW = 52f
            context.fillStyle(Color(AppColor.PRIMARY_SOFT))
            context.beginPath()
            context.moveTo(width - priceTagW, hy - 9f)
            context.lineTo(width, hy - 9f)
            context.lineTo(width, hy + 9f)
            context.lineTo(width - priceTagW, hy + 9f)
            context.closePath()
            context.fill()
            context.fillStyle(Color.WHITE)
            context.font(9f)
            context.textAlign(TextAlign.CENTER)
            context.fillText(fmt2(priceAtY), width - priceTagW / 2f, hy + 3f)

            if (k != null) {
                val dateTagW = 62f
                val dateTagX = (cx - dateTagW / 2f).coerceIn(0f, width - dateTagW)
                context.fillStyle(Color(AppColor.PRIMARY_SOFT))
                context.beginPath()
                context.moveTo(dateTagX, dateY - 12f)
                context.lineTo(dateTagX + dateTagW, dateY - 12f)
                context.lineTo(dateTagX + dateTagW, dateY + 2f)
                context.lineTo(dateTagX, dateY + 2f)
                context.closePath()
                context.fill()
                context.fillStyle(Color.WHITE)
                context.font(9f)
                context.textAlign(TextAlign.CENTER)
                context.fillText(k.tradeDate, dateTagX + dateTagW / 2f, dateY - 3f)
            }

            if (k != null && (interaction is InteractionState.Locked || interaction is InteractionState.Hover)) {
                context.strokeStyle(Color(AppColor.PRIMARY_SOFT))
                context.lineWidth(1.5f)
                context.beginPath()
                context.moveTo(cx - cw / 2f - 2f, py(k.high) - 2f)
                context.lineTo(cx + cw / 2f + 2f, py(k.high) - 2f)
                context.lineTo(cx + cw / 2f + 2f, py(k.low) + 2f)
                context.lineTo(cx - cw / 2f - 2f, py(k.low) + 2f)
                context.closePath()
                context.stroke()
            }

            if (interaction is InteractionState.RangeSelect) {
                val startLocal = interaction.startGlobalIdx - visibleStart
                val endLocal = interaction.endGlobalIdx - visibleStart
                if (startLocal in visible.indices || endLocal in visible.indices) {
                    val lo = minOf(startLocal, endLocal).coerceIn(0, nVisible - 1)
                    val hi = maxOf(startLocal, endLocal).coerceIn(0, nVisible - 1)
                    val x0 = step * lo
                    val x1 = step * (hi + 1)
                    context.fillStyle(Color(0x1A1976D2))
                    context.beginPath()
                    context.moveTo(x0, padT)
                    context.lineTo(x1, padT)
                    context.lineTo(x1, volTop)
                    context.lineTo(x0, volTop)
                    context.closePath()
                    context.fill()
                    context.strokeStyle(Color(0x801976D2))
                    context.lineWidth(1f)
                    context.beginPath()
                    context.moveTo(x0, padT); context.lineTo(x0, volTop)
                    context.moveTo(x1, padT); context.lineTo(x1, volTop)
                    context.stroke()
                }
            }

            if (k != null) {
                context.fillStyle(Color(0xE622263F))
                context.beginPath()
                context.moveTo(0f, 2f)
                context.lineTo(width, 2f)
                context.lineTo(width, tooltipH)
                context.lineTo(0f, tooltipH)
                context.closePath()
                context.fill()

                val up = k.close >= k.open
                val pct = if (k.open != 0.0) (k.close - k.open) / k.open * 100.0 else 0.0
                val tooltipColor = if (up) Color(0xFFFF8A80) else Color(0xFFA5D6A7)
                context.font(9f)
                context.textAlign(TextAlign.LEFT)
                context.fillStyle(Color.WHITE)
                context.fillText(
                    "${k.tradeDate} 开${fmt2(k.open)} 收${fmt2(k.close)} 高${fmt2(k.high)} 低${fmt2(k.low)}",
                    4f, 12f
                )
                context.fillStyle(tooltipColor)
                context.fillText(
                    "涨跌 ${fmtSignedPct(pct)} 量${k.volume.toInt()}手 ${if (ctx.klineShowMA) "MA5 ${maAt(activeIdx,5)?.let { fmt2(it) } ?: "-"}" else ""}",
                    4f, 24f
                )

                if (aiLevels.isNotEmpty()) {
                    val nearest = aiLevels.minByOrNull { abs(it.price - k.close) }
                    nearest?.let {
                        val dist = (k.close - it.price) / it.price * 100.0
                        context.fillStyle(Color(0xFFFFE082))
                        context.fillText("距${it.label} ${fmtSignedPct(dist)}", 4f, 34f)
                    }
                }
            }

            ctx.rangeStats?.let { stats ->
                val statsH = 28f
                val statsY = padT + 4f
                context.fillStyle(Color(0xE622263F))
                context.beginPath()
                context.moveTo(0f, statsY)
                context.lineTo(width, statsY)
                context.lineTo(width, statsY + statsH)
                context.lineTo(0f, statsY + statsH)
                context.closePath()
                context.fill()
                context.fillStyle(Color(0xFFFFE082))
                context.font(9f)
                context.textAlign(TextAlign.LEFT)
                context.fillText("区间统计: ${stats.summary}", 4f, statsY + 12f)
                context.fillStyle(Color(AppColor.TEXT_SUB))
                context.fillText("${stats.startDate} ~ ${stats.endDate}", 4f, statsY + 24f)
            }
        }

        if (ctx.klineShowMA && !showCrosshair) {
            context.font(9f)
            context.textAlign(TextAlign.LEFT)
            var lx = 4f
            listOf(
                Triple("MA5", Color(AppColor.PRIMARY_SOFT), ma5Points.lastOrNull()?.second),
                Triple("MA10", Color(AppColor.WARNING), ma10Points.lastOrNull()?.second),
                Triple("MA20", Color(0xFF7B1FA2), ma20Points.lastOrNull()?.second)
            ).forEach { (label, color, _) ->
                context.fillStyle(color)
                context.fillText(label, lx, padT - 6f)
                lx += 36f
            }
        }

        val focus = ctx.pendingFocus
        if (focus != null) {
            val alpha = 1f
            if (alpha > 0f) {
                fun withAlpha(c: Long, a: Float): Color {
                    val base = c and 0x00FFFFFF
                    val aa = ((0xFF * a).toInt().coerceIn(0, 255)).toLong() shl 24
                    return Color(base or aa)
                }
                fun cxOf(idx: Int): Float = step * idx + step / 2f
                when (focus) {
                    is KlineFocus.Range -> {
                        val s = visible.indexOfFirst { normalizedTradeDate(it.tradeDate) == normalizedTradeDate(focus.start) }
                        val e = visible.indexOfFirst { normalizedTradeDate(it.tradeDate) == normalizedTradeDate(focus.end) }.let { if (it < 0) s else it }
                        if (s >= 0) {
                            val lo = minOf(s, e); val hi = maxOf(s, e)
                            val x0 = cxOf(lo) - cw / 2
                            val x1 = cxOf(hi) + cw / 2
                            val rectH = volTop - padT

                            context.beginPath()
                            context.moveTo(x0, padT)
                            context.lineTo(x1, padT)
                            context.lineTo(x1, padT + rectH)
                            context.lineTo(x0, padT + rectH)
                            context.lineTo(x0, padT)
                            context.fillStyle(withAlpha(focus.colorValue, 0.14f * alpha))
                            context.fill()

                            context.beginPath()
                            context.moveTo(x0, padT)
                            context.lineTo(x1, padT)
                            context.lineTo(x1, padT + rectH)
                            context.lineTo(x0, padT + rectH)
                            context.lineTo(x0, padT)
                            context.strokeStyle(withAlpha(focus.colorValue, 0.8f * alpha))
                            context.lineWidth(1f)
                            context.stroke()
                        }
                    }
                    is KlineFocus.Point -> {
                        val i = visible.indexOfFirst { normalizedTradeDate(it.tradeDate) == normalizedTradeDate(focus.date) }
                        if (i >= 0) {
                            val x = cxOf(i)
                            context.beginPath()
                            context.moveTo(x, padT)
                            context.lineTo(x, volTop)
                            context.strokeStyle(withAlpha(focus.colorValue, alpha))
                            context.lineWidth(1.5f)
                            context.stroke()
                        }
                    }
                    is KlineFocus.Price -> {
                        val y = py(focus.value)
                        if (y in padT..volTop) {
                            var x = 0f
                            context.beginPath()
                            while (x < width) {
                                context.moveTo(x, y)
                                context.lineTo(minOf(x + 6f, width), y)
                                x += 10f
                            }
                            context.strokeStyle(withAlpha(focus.colorValue, alpha))
                            context.lineWidth(1f)
                            context.stroke()
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.klineSummary(ctx: StockDetailPage, visible: List<KLineDataItem>) {
    val latest = visible.lastOrNull() ?: ctx.stockDetail?.kline?.lastOrNull()
    if (latest == null) return

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
        }

        View {
            attr {
                height(1f)
                backgroundColor(AppColor.DIVIDER_SOFT)
                marginBottom(8f)
            }
        }

        View {
            attr {
                flexDirectionRow()
                flexWrapWrap()
            }

            Text {
                attr {
                    text("最新: ${latest.tradeDate}")
                    fontSize(11f)
                    color(AppColor.TEXT_GRAY)
                }
            }

            Text {
                attr {
                    text("收盘: ${fmt2(latest.close)}")
                    fontSize(11f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                    marginLeft(10f)
                }
            }

            Text {
                attr {
                    text("量: ${latest.volume.toInt()}手")
                    fontSize(11f)
                    color(AppColor.TEXT_GRAY)
                    marginLeft(10f)
                }
            }

            vif({ ctx.selectedKlineIndex >= 0 }) {
                Text {
                    attr {
                        text("已选 ${ctx.getAggregatedKline().getOrNull(ctx.selectedKlineIndex)?.tradeDate ?: ""}")
                        fontSize(11f)
                        color(AppColor.PRIMARY_SOFT)
                        marginLeft(10f)
                    }
                }
            }
        }
    }
}
