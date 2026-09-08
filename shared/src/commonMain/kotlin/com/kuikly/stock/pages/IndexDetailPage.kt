// 指数详情页：基础信息、实时点位、日K走势，以及 AI 解读入口。
// 由聊天页 index_card 跳转承接（openPage("index_detail", {code})）。
// 注意：指数代码与个股代码存在重叠（如 000001），本页只读 index_* 表，绝不调用个股 minute/orderBook/indicator；
// 也不提供自选按钮（WatchStore 以 code 为键，会与同代码个股冲突）。

package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.TextAlign
import com.kuikly.stock.data.StockRepository
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch

@Page("index_detail")
class IndexDetailPage : Pager() {

    internal var indexCode by observable("")

    internal var indexDetail by observable<StockDetailData?>(null)

    internal var aiAnalysis by observable<AIAnalysisData?>(null)

    internal var isLoading by observable(true)

    internal var isAnalyzing by observable(false)

    internal var loadErrorMessage by observable("")

    internal var dataSourceText by observable("")

    internal var selectedKlineIndex by observable(-1)

    internal var klineCanvasWidth by observable(0f)

    override fun didInit() {
        super.didInit()
        indexCode = pagerData.params.optString("code", "")

        if (indexCode.isNotEmpty()) {
            loadIndexDetail()
            loadDataSource()
        }
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

                vif({ ctx.isLoading }) {
                    stockDetailLoadingView()
                }
                velseif({ ctx.indexDetail == null }) {
                    indexErrorView(ctx)
                }
                velse {
                    indexNavigationBar(ctx)

                    Scroller {
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            scrollEnable(true)
                        }

                        indexInfoCard(ctx)

                        indexRealtimeCard(ctx)

                        indexKlineChartArea(ctx)

                        indexAiAnalysisCards(ctx)

                        vif({ ctx.dataSourceText.isNotEmpty() }) {
                            indexDataSourceFooter(ctx)
                        }
                    }
                }
            }
        }
    }

    internal fun loadIndexDetail() {
        if (indexCode.isEmpty()) return
        isLoading = true

        lifecycleScope.launch {
            try {
                val data = StockRepository.loadIndexDetail(indexCode)
                delay(0)
                if (data != null) {
                    indexDetail = data
                } else {
                    indexDetail = null
                    loadErrorMessage = "未找到指数 $indexCode 的数据（可能是旧版数据库，更新后重试）"
                }
            } catch (e: Throwable) {
                delay(0)
                indexDetail = null
                loadErrorMessage = e.message ?: "数据加载失败"
            } finally {
                isLoading = false
            }
        }
    }

    internal fun loadDataSource() {
        lifecycleScope.launch {
            try {
                val list = StockRepository.dataSources()
                delay(0)
                val labels = linkedMapOf(
                    "index_realtime" to "行情",
                    "index_daily_kline" to "K线",
                )
                val kv = list.toMap()
                dataSourceText = labels.entries.mapNotNull { (k, label) ->
                    kv[k]?.let { "$label：$it" }
                }.joinToString(" · ")
            } catch (e: Throwable) {
                delay(0)
                dataSourceText = ""
            }
        }
    }

    internal fun triggerAIAnalysis() {
        if (indexCode.isEmpty() || isAnalyzing) return
        isAnalyzing = true
        lifecycleScope.launch {
            try {
                val result = StockRepository.analyzeIndex(indexCode)
                delay(0)
                aiAnalysis = result
            } catch (e: Throwable) {
                delay(0)
                aiAnalysis = null
            } finally {
                isAnalyzing = false
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexNavigationBar(ctx: IndexDetailPage) {
    val name = ctx.indexDetail?.info?.name ?: "指数"
    val code = ctx.indexDetail?.info?.code ?: ctx.indexCode

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(0xFF2E7D32)
            paddingTop(ctx.pagerData.statusBarHeight)
            height(48f + ctx.pagerData.statusBarHeight)
        }

        View {
            attr { padding(12f, 16f, 12f, 16f) }
            event {
                click {
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                }
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
                text(name)
                fontSize(16f)
                fontWeightBold()
                color(0xFFFFFFFF)
                marginLeft(8f)
            }
        }

        Text {
            attr {
                text("($code)")
                fontSize(12f)
                color(0xFFC8E6C9)
                marginLeft(4f)
            }
        }

        View { attr { flex(1f) } }

        View {
            attr { padding(10f, 12f, 10f, 12f) }
            event { click { ctx.triggerAIAnalysis() } }
            Text {
                attr {
                    text("AI分析")
                    fontSize(13f)
                    color(0xFFFFFFFF)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexInfoCard(ctx: IndexDetailPage) {
    val info = ctx.indexDetail?.info ?: return

    View {
        attr {
            flexDirectionColumn()
            margin(8f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        Text {
            attr {
                text("基础信息")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

        infoItem("指数代码", info.code)
        infoItem("指数名称", info.name ?: "-")
        infoItem("所属市场", info.plate ?: "-")
    }
}

internal fun ViewContainer<*, *>.indexRealtimeCard(ctx: IndexDetailPage) {
    val realtime = ctx.indexDetail?.realtime ?: return
    val pct = realtime.changePercent
    val priceColor = when {
        pct == null || pct == 0.0 -> 0xFF999999
        pct > 0 -> 0xFFE53935
        else -> 0xFF43A047
    }

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        Text {
            attr {
                text("实时行情")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

        View {
            attr {
                flexDirectionRow()
                marginBottom(8f)
            }

            indexQuoteColumn("最新点位",
                realtime.price?.let { String.format("%.2f", it) } ?: "-",
                26f, priceColor)
            indexQuoteColumn("涨跌点",
                realtime.change?.let { String.format("%+.2f", it) } ?: "-",
                15f, priceColor)
            indexQuoteColumn("涨跌幅",
                realtime.changePercent?.let { String.format("%+.2f%%", it) } ?: "-",
                15f, priceColor)
        }

        View {
            attr {
                flexDirectionRow()
                flexWrap(FlexWrap.WRAP)
            }

            indexQuoteItem(ctx.pagerData.pageViewWidth, "开盘", realtime.openPrice)
            indexQuoteItem(ctx.pagerData.pageViewWidth, "昨收", realtime.preClose)
            indexQuoteItem(ctx.pagerData.pageViewWidth, "最高", realtime.high)
            indexQuoteItem(ctx.pagerData.pageViewWidth, "最低", realtime.low)
        }

        View {
            attr {
                flexDirectionRow()
                marginTop(8f)
                flexWrap(FlexWrap.WRAP)
            }

            indexQuoteItem(ctx.pagerData.pageViewWidth, "成交量", realtime.volume, ::fmtIndexVolume)
            indexQuoteItem(ctx.pagerData.pageViewWidth, "成交额", realtime.amount, ::fmtIndexAmount)
        }
    }
}

internal fun ViewContainer<*, *>.indexQuoteItem(
    pageViewWidth: Float,
    label: String,
    value: Double?,
    format: ((Double) -> String)? = null
) {
    View {
        attr {
            width((pageViewWidth - 40f) / 4f)
            flexDirectionColumn()
            marginTop(4f)
        }

        Text {
            attr {
                text(label)
                fontSize(11f)
                color(0xFF999999)
            }
        }

        Text {
            attr {
                text(value?.let { format?.invoke(it) ?: String.format("%.2f", it) } ?: "-")
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexQuoteColumn(
    label: String,
    value: String,
    valueSize: Float,
    color: Long
) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
        }

        Text {
            attr {
                text(label)
                fontSize(11f)
                color(0xFF999999)
            }
        }

        Text {
            attr {
                text(value)
                fontSize(valueSize)
                fontWeightBold()
                color(color)
                marginTop(2f)
            }
        }
    }
}

internal fun fmtIndexVolume(v: Double): String = when {
    v >= 100000000 -> String.format("%.2f亿股", v / 100000000)
    v >= 10000 -> String.format("%.2f万股", v / 10000)
    else -> String.format("%.0f股", v)
}

internal fun fmtIndexAmount(v: Double): String = when {
    v >= 100000000 -> String.format("%.2f亿元", v / 100000000)
    v >= 10000 -> String.format("%.2f万元", v / 10000)
    else -> String.format("%.0f元", v)
}

internal fun ViewContainer<*, *>.indexDataSourceFooter(ctx: IndexDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 10f, left = 16f, bottom = 10f, right = 16f)
            backgroundColor(0xFFFAFAFA)
            borderRadius(8f)
        }

        Text {
            attr {
                text("数据来源")
                fontSize(11f)
                color(0xFF999999)
                marginBottom(4f)
            }
        }

        Text {
            attr {
                text(ctx.dataSourceText)
                fontSize(11f)
                color(0xFF888888)
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexKlineChartArea(ctx: IndexDetailPage) {
    val klineData = ctx.indexDetail?.kline

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 10f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        Text {
            attr {
                text("K线走势（近30日）")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(6f)
            }
        }

        vif({ ctx.isLoading }) {
            klineLoadingView()
        }
        velseif({ klineData != null && klineData.isNotEmpty() }) {
            indexKlineChartCanvas(ctx, klineData!!)
            indexKlineSummary(klineData)
        }
        velse {
            indexKlineErrorView(ctx)
        }
    }
}

internal fun ViewContainer<*, *>.indexKlineErrorView(ctx: IndexDetailPage) {
    View {
        attr {
            padding(top = 24f, left = 0f, bottom = 24f, right = 0f)
            alignItems(FlexAlign.CENTER)
            flexDirectionColumn()
        }
        Text {
            attr {
                text("指数K线暂无数据（旧版数据库请更新后重试）")
                fontSize(13f)
                color(0xFF999999)
                textAlignCenter()
            }
        }
        View {
            attr {
                marginTop(12f)
                padding(top = 8f, left = 20f, bottom = 8f, right = 20f)
                backgroundColor(0xFFE8F5E9)
                borderRadius(16f)
            }
            event {
                click { ctx.loadIndexDetail() }
            }
            Text {
                attr {
                    text("重试")
                    fontSize(13f)
                    fontWeightBold()
                    color(0xFF2E7D32)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexKlineChartCanvas(ctx: IndexDetailPage, klineData: List<KLineDataItem>) {
    Canvas({
        attr {
            height(344f)
            marginTop(2f)
        }
        event {
            longPress { params ->
                if (ctx.klineCanvasWidth <= 0f) return@longPress
                val i = ((params.x / ctx.klineCanvasWidth) * klineData.size)
                    .toInt().coerceIn(0, klineData.size - 1)
                ctx.selectedKlineIndex = i
            }
        }
    }) { context, width, height ->
        val n = klineData.size
        if (n == 0 || width <= 0f || height <= 0f) return@Canvas

        if (ctx.klineCanvasWidth != width) {
            ctx.klineCanvasWidth = width
        }

        val tooltipH = 26f
        val padT = 30f
        val volTop = height - 62f
        val volH = 40f
        val dateY = height - 12f

        val all = klineData.flatMap { listOf(it.high, it.low, it.open, it.close) }
        var minP = all.minOrNull() ?: 0.0
        var maxP = all.maxOrNull() ?: 1.0
        if (maxP <= minP) maxP = minP + 1.0

        val chartH = volTop - padT - 6f
        fun py(p: Double): Float = padT + chartH * ((maxP - p) / (maxP - minP)).toFloat()

        val step = width / n
        val cw = (step * 0.55f).coerceAtLeast(1.5f)

        context.strokeStyle(Color(0xFFEDEDED))
        context.lineWidth(1f)
        for (i in 0..4) {
            val gy = padT + chartH * i / 4f
            context.beginPath()
            context.moveTo(0f, gy)
            context.lineTo(width, gy)
            context.stroke()
        }

        klineData.forEachIndexed { i, k ->
            val cx = step * i + step / 2f
            val up = k.close >= k.open
            val color = if (up) Color(0xFFE53935) else Color(0xFF43A047)
            context.strokeStyle(color)
            context.lineWidth(1f)
            context.beginPath()
            context.moveTo(cx, py(k.high))
            context.lineTo(cx, py(k.low))
            context.stroke()
            val yo = py(k.open)
            val yc = py(k.close)
            val top = minOf(yo, yc)
            val bh = kotlin.math.abs(yo - yc).coerceAtLeast(1.2f)
            context.fillStyle(color)
            context.beginPath()
            context.moveTo(cx - cw / 2f, top)
            context.lineTo(cx + cw / 2f, top)
            context.lineTo(cx + cw / 2f, top + bh)
            context.lineTo(cx - cw / 2f, top + bh)
            context.closePath()
            context.fill()
        }

        val maxVol = klineData.maxOf { it.volume }.toFloat().coerceAtLeast(1f)
        context.strokeStyle(Color(0xFFE0E0E0))
        context.lineWidth(1f)
        context.beginPath()
        context.moveTo(0f, volTop - 5f)
        context.lineTo(width, volTop - 5f)
        context.stroke()
        klineData.forEachIndexed { i, k ->
            val cx = step * i + step / 2f
            val up = k.close >= k.open
            val color = if (up) Color(0xFFE53935) else Color(0xFF43A047)
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
        context.fillStyle(Color(0xFF999999))
        context.font(9f)
        context.textAlign(TextAlign.RIGHT)
        context.fillText("成交量", width - 2f, volTop - 8f)

        context.fillStyle(Color(0xFF999999))
        context.font(10f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(String.format("%.2f", maxP), 4f, padT + 9f)
        context.fillText(String.format("%.2f", minP), 4f, volTop - 6f)

        val firstDate = klineData.first().tradeDate
        val lastDate = klineData.last().tradeDate
        context.font(9f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(firstDate, 2f, dateY)
        context.textAlign(TextAlign.RIGHT)
        context.fillText(lastDate, width - 2f, dateY)

        val sel = ctx.selectedKlineIndex
        if (sel >= 0 && sel < n) {
            val k = klineData[sel]
            val cx = step * sel + step / 2f
            context.strokeStyle(Color(0xFF333333))
            context.lineWidth(1.5f)
            context.beginPath()
            context.moveTo(cx - cw / 2f - 1f, py(k.high) - 1f)
            context.lineTo(cx + cw / 2f + 1f, py(k.high) - 1f)
            context.lineTo(cx + cw / 2f + 1f, py(k.low) + 1f)
            context.lineTo(cx - cw / 2f - 1f, py(k.low) + 1f)
            context.closePath()
            context.stroke()

            context.fillStyle(Color(0xE6333333))
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
            context.font(8f)
            context.textAlign(TextAlign.LEFT)
            context.fillStyle(Color(0xFFFFFFFF))
            context.fillText(
                "${k.tradeDate}  开 ${String.format("%.2f", k.open)}  收 ${String.format("%.2f", k.close)}" +
                    "  高 ${String.format("%.2f", k.high)}  低 ${String.format("%.2f", k.low)}",
                4f, 10f
            )
            context.fillStyle(tooltipColor)
            context.fillText(
                "涨跌 ${String.format("%+.2f%%", pct)}   量 ${fmtIndexVolume(k.volume)}",
                4f, 20f
            )
        }
    }
}

internal fun ViewContainer<*, *>.indexKlineSummary(klineData: List<KLineDataItem>) {
    val latest = klineData.lastOrNull()
    if (latest == null) return

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
        }

        View {
            attr {
                height(1f)
                backgroundColor(0xFFEEEEEE)
                marginBottom(8f)
            }
        }

        View {
            attr {
                flexDirectionRow()
            }

            Text {
                attr {
                    text("最新: ${latest.tradeDate}")
                    fontSize(12f)
                    color(0xFF666666)
                }
            }

            Text {
                attr {
                    text("收盘: ${latest.close}")
                    fontSize(12f)
                    fontWeightBold()
                    color(0xFF333333)
                    marginLeft(12f)
                }
            }

            Text {
                attr {
                    text("成交量: ${fmtIndexVolume(latest.volume)}")
                    fontSize(12f)
                    color(0xFF666666)
                    marginLeft(12f)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexAiAnalysisCards(ctx: IndexDetailPage) {
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
                }
            }

            vif({ !ctx.isAnalyzing }) {
                View {
                    attr {
                        marginLeft(8f)
                        padding(top = 4f, left = 8f, bottom = 4f, right = 8f)
                        backgroundColor(0xFFE8F5E9)
                        borderRadius(12f)
                    }
                    event {
                        click {
                            ctx.triggerAIAnalysis()
                        }
                    }
                    Text {
                        attr {
                            text("刷新分析")
                            fontSize(11f)
                            color(0xFF666666)
                        }
                    }
                }
            }
        }

        vif({ ctx.isAnalyzing }) {
            indexAnalyzingView()
        }
        velseif({ ctx.aiAnalysis == null }) {
            indexNotAnalyzedView(ctx)
        }
        velse {
            indexAnalysisBubble(ctx, ctx.aiAnalysis!!)
        }
    }
}

internal fun ViewContainer<*, *>.indexAnalysisBubble(ctx: IndexDetailPage, analysis: AIAnalysisData) {
    val text = buildAnalysisMarkdown(analysis)

    View {
        attr {
            flexDirectionRow()
            marginTop(6f)
            justifyContent(FlexJustifyContent.FLEX_START)
        }
        View {
            val bubbleW = ctx.pagerData.pageViewWidth - 96f
            attr {
                flexDirectionColumn()
                width(bubbleW)
                backgroundColor(0xFFEAF4EA)
                borderRadius(12f)
                padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            }
            renderMarkdown(text)
        }
    }
}

internal fun ViewContainer<*, *>.indexAnalyzingView() {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 14f, left = 16f, bottom = 14f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        Text {
            attr {
                text("AI 正在分析中...")
                fontSize(14f)
                color(0xFF666666)
            }
        }

        Text {
            attr {
                text("请稍候，当前 AI 服务正在生成指数分析报告")
                fontSize(12f)
                color(0xFF999999)
                marginTop(6f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexNotAnalyzedView(ctx: IndexDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 14f, left = 16f, bottom = 14f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        Text {
            attr {
                text("尚未进行 AI 分析")
                fontSize(14f)
                color(0xFF666666)
            }
        }

        View {
            attr {
                marginTop(12f)
                padding(top = 10f, left = 24f, bottom = 10f, right = 24f)
                backgroundColor(0xFF2E7D32)
                borderRadius(20f)
            }
            event {
                click {
                    ctx.triggerAIAnalysis()
                }
            }
            Text {
                attr {
                    text("开始 AI 分析")
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFFFFFFFF)
                }
            }
        }

        Text {
            attr {
                text("AI 将为您分析指数趋势、点位、风险并给出操作参考")
                fontSize(11f)
                color(0xFF999999)
                marginTop(8f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexErrorView(ctx: IndexDetailPage) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }

        Text {
            attr {
                text("加载失败")
                fontSize(16f)
                color(0xFFE53935)
            }
        }

        Text {
            attr {
                text(ctx.loadErrorMessage.ifEmpty { "未找到指数 ${ctx.indexCode} 的数据" })
                fontSize(13f)
                color(0xFF999999)
                marginTop(8f)
                textAlignCenter()
            }
        }

        View {
            attr {
                marginTop(16f)
                padding(top = 10f, left = 24f, bottom = 10f, right = 24f)
                backgroundColor(0xFF2E7D32)
                borderRadius(20f)
            }
            event {
                click {
                    ctx.loadIndexDetail()
                }
            }
            Text {
                attr {
                    text("重试")
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFFFFFFFF)
                }
            }
        }
    }
}
