// 个股详情页：基础信息、实时行情、技术指标、分时与五档盘口，以及 AI 分析入口。

package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
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
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuiklybase.KuiklyMarkdown
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmt3
import com.kuikly.stock.data.fmtSigned2
import com.kuikly.stock.data.fmtSignedPct

@Page("stock_detail")
class StockDetailPage : Pager() {

    internal var stockCode by observable("")

    internal var stockDetail by observable<StockDetailData?>(null)

    internal var aiAnalysis by observable<AIAnalysisData?>(null)

    internal var isLoading by observable(true)

    internal var isAnalyzing by observable(false)

    internal var minuteData by observable<List<MinutePoint>?>(null)

    internal var orderBook by observable<OrderBookData?>(null)

    internal var loadErrorMessage by observable("")

    internal var dataSourceText by observable("")

    internal var selectedKlineIndex by observable(-1)

    internal var klineCanvasWidth by observable(0f)

    internal var watched by observable(false)

    override fun didInit() {
        super.didInit()
        stockCode = pagerData.params.optString("code", "")
        watched = stockCode.isNotEmpty() && WatchStore.isWatched(stockCode)

        if (stockCode.isNotEmpty()) {
            loadStockDetail()
            loadExtraQuote()
            loadDataSource()
        }
    }

    internal fun toggleWatch() {
        val code = stockCode
        if (code.isEmpty()) return
        val name = stockDetail?.info?.name?.takeIf { it.isNotBlank() }
            ?: WatchStore.find(code)?.name
            ?: code
        WatchStore.toggle(code, name)
        watched = WatchStore.isWatched(code)
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
                velseif({ ctx.stockDetail == null }) {
                    errorView(ctx)
                }
                velse {
                    detailNavigationBar(ctx)

                    Scroller {
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            scrollEnable(true)
                        }

                        infoCard(ctx)

                        realtimeCard(ctx)

                        indicatorCard(ctx)

                        klineChartArea(ctx)

                        minuteCard(ctx)

                        orderBookCard(ctx)

                        aiAnalysisCards(ctx)

                        vif({ ctx.dataSourceText.isNotEmpty() }) {
                            dataSourceFooter(ctx)
                        }
                    }
                }
            }
        }
    }

    internal fun loadStockDetail() {
        if (stockCode.isEmpty()) return
        isLoading = true

        lifecycleScope.launch {
            try {
                val data = StockRepository.loadStockDetail(stockCode)
                delay(0)
                if (data != null) {
                    stockDetail = data
                } else {
                    stockDetail = null
                    loadErrorMessage = "未找到股票 $stockCode 的数据"
                }
            } catch (e: Throwable) {
                delay(0)
                stockDetail = null
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
                    "stock_realtime" to "行情",
                    "stock_daily_kline" to "K线",
                    "stock_minute" to "分时",
                    "stock_order_book" to "盘口",
                    "stock_indicator" to "指标",
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
        if (stockCode.isEmpty() || isAnalyzing) return
        isAnalyzing = true
        lifecycleScope.launch {
            try {
                val result = StockRepository.analyzeStock(stockCode)
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

    internal fun loadExtraQuote() {
        if (stockCode.isEmpty()) return
        lifecycleScope.launch {
            try {
                val minute = StockRepository.loadMinute(stockCode)
                delay(0)
                minuteData = minute
                val book = StockRepository.loadOrderBook(stockCode)
                delay(0)
                orderBook = book
            } catch (e: Throwable) {
                delay(0)
            }
        }
    }

    private fun getMockName(code: String): String {
        return when (code) {
            "000001" -> "平安银行"
            "600519" -> "贵州茅台"
            "000002" -> "万科A"
            "600036" -> "招商银行"
            "300750" -> "宁德时代"
            else -> "未知股票"
        }
    }
}

internal fun ViewContainer<*, *>.detailNavigationBar(ctx: StockDetailPage) {
    val name = ctx.stockDetail?.info?.name ?: "未知"
    val code = ctx.stockDetail?.info?.code ?: ctx.stockCode

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(0xFF1976D2)
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
                color(0xFFB3D9FF)
                marginLeft(4f)
            }
        }

        View { attr { flex(1f) } }

        View {
            attr { padding(10f, 12f, 6f, 12f) }
            event { click { ctx.toggleWatch() } }
            Text {
                attr {
                    text(if (ctx.watched) "★" else "☆")
                    fontSize(20f)
                    color(0xFFFFFFFF)
                }
            }
        }

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

internal fun ViewContainer<*, *>.infoCard(ctx: StockDetailPage) {
    val info = ctx.stockDetail?.info ?: return

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

        infoItem("股票代码", info.code)
        infoItem("股票名称", info.name ?: "-")
        infoItem("所属行业", info.industry ?: "-")
        infoItem("市场板块", info.plate ?: "-")
        infoItem("上市日期", info.listDate ?: "-")
    }
}

internal fun ViewContainer<*, *>.infoItem(label: String, value: String) {
    View {
        attr {
            flexDirectionRow()
            marginTop(6f)
        }

        Text {
            attr {
                text(label)
                fontSize(13f)
                color(0xFF666666)
                width(80f)
            }
        }

        Text {
            attr {
                text(value)
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
                flex(1f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.realtimeCard(ctx: StockDetailPage) {
    val realtime = ctx.stockDetail?.realtime ?: return
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

            quoteColumn(ctx, "最新价",
                realtime.price?.let { fmt2(it) } ?: "-",
                26f, priceColor)
            quoteColumn(ctx, "涨跌额",
                realtime.change?.let { fmtSigned2(it) } ?: "-",
                15f, priceColor)
            quoteColumn(ctx, "涨跌幅",
                realtime.changePercent?.let { fmtSignedPct(it) } ?: "-",
                15f, priceColor)
        }

        View {
            attr {
                flexDirectionRow()
                flexWrap(FlexWrap.WRAP)
            }

            quoteItem(ctx, "开盘", realtime.openPrice, "")
            quoteItem(ctx, "昨收", realtime.preClose, "")
            quoteItem(ctx, "最高", realtime.high, "")
            quoteItem(ctx, "最低", realtime.low, "")
        }

        View {
            attr {
                flexDirectionRow()
                marginTop(8f)
                flexWrap(FlexWrap.WRAP)
            }

            quoteItem(ctx, "成交量", realtime.volume, "手")
            quoteItem(ctx, "成交额", realtime.amount, "元")
            quoteItem(ctx, "市盈率", realtime.peTtm, "")
            quoteItem(ctx, "市净率", realtime.pb, "")
        }
    }
}

internal fun ViewContainer<*, *>.quoteItem(
    ctx: StockDetailPage,
    label: String,
    value: Double?,
    suffix: String = ""
) {
    View {
        attr {
            width((ctx.pagerData.pageViewWidth - 40f) / 4f)
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
                text(value?.let { fmt2(it) } ?: "-$suffix")
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
    }
}

internal fun ViewContainer<*, *>.quoteColumn(
    ctx: StockDetailPage,
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

internal fun ViewContainer<*, *>.dataSourceFooter(ctx: StockDetailPage) {
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

internal fun ViewContainer<*, *>.indicatorCard(ctx: StockDetailPage) {
    val ind = ctx.stockDetail?.indicator ?: return

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
                text("技术指标（${ind.tradeDate}）")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

        indicatorItem("MA5", ind.ma5)
        indicatorItem("MA10", ind.ma10)
        indicatorItem("MA20", ind.ma20)

        View {
            attr { height(1f); backgroundColor(0xFFEEEEEE); margin(8f, 0f, 8f, 0f) }
        }

        View {
            attr { flexDirectionRow(); marginTop(6f) }
            Text { attr { text("MACD"); fontSize(12f); color(0xFF666666); width(60f) } }
            Text {
                attr {
                    text("DIF ${fmtInd(ind.dif)}  DEA ${fmtInd(ind.dea)}  柱 ${fmtInd(ind.macd)}")
                    fontSize(12f); color(0xFF333333)
                }
            }
        }
        View {
            attr { flexDirectionRow(); marginTop(6f) }
            Text { attr { text("RSI6"); fontSize(12f); color(0xFF666666); width(60f) } }
            Text { attr { text(fmtInd(ind.rsi6)); fontSize(12f); color(0xFF333333) } }
        }
        View {
            attr { flexDirectionRow(); marginTop(6f) }
            Text { attr { text("KDJ"); fontSize(12f); color(0xFF666666); width(60f) } }
            Text {
                attr {
                    text("K ${fmtInd(ind.kdjK)}  D ${fmtInd(ind.kdjD)}  J ${fmtInd(ind.kdjJ)}")
                    fontSize(12f); color(0xFF333333)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indicatorItem(label: String, value: Double?) {
    View {
        attr { flexDirectionRow(); marginTop(4f) }
        Text { attr { text(label); fontSize(12f); color(0xFF666666); width(60f) } }
        Text { attr { text(fmtInd(value)); fontSize(12f); color(0xFF333333) } }
    }
}

private fun fmtInd(v: Double?): String = if (v == null) "-" else fmt3(v)

internal fun ViewContainer<*, *>.minuteCard(ctx: StockDetailPage) {
    val data = ctx.minuteData ?: return
    if (data.isEmpty()) return // 无分时数据（iOS/JS 包内无分钟级快照）时整卡隐藏

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
                text("分时数据（共 ${data.size} 分钟）")
                fontSize(15f); fontWeightBold(); color(0xFF333333); marginBottom(8f)
            }
        }

        val latest = data.lastOrNull()
        if (latest != null) {
            View {
                attr { flexDirectionRow() }
                Text { attr { text("最新分时"); fontSize(12f); color(0xFF666666); width(72f) } }
                Text {
                    attr {
                        text("${latest.time}  价 ${fmt2(latest.price)}  均价 ${fmtOpt(latest.avgPrice)}")
                        fontSize(12f); color(0xFF333333)
                    }
                }
            }
            View {
                attr { flexDirectionRow(); marginTop(4f) }
                Text { attr { text("区间"); fontSize(12f); color(0xFF666666); width(72f) } }
                val prices = data.map { it.price }
                val hi = prices.maxOrNull(); val lo = prices.minOrNull()
                Text {
                    attr { text("高 ${fmtOpt(hi)}  低 ${fmtOpt(lo)}"); fontSize(12f); color(0xFF333333) }
                }
            }
        }
        Text {
            attr { text("提示：分时仅 11 只热门股有数据"); fontSize(11f); color(0xFF999999); marginTop(6f) }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookCard(ctx: StockDetailPage) {
    val book = ctx.orderBook ?: return

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
                text("五档盘口${book.updateTime?.let { "（$it）" } ?: ""}")
                fontSize(15f); fontWeightBold(); color(0xFF333333); marginBottom(8f)
            }
        }

        book.asks.reversed().forEachIndexed { i, (price, vol) ->
            orderBookRow("卖${5 - i}", price, vol, 0xFF43A047)
        }
        View { attr { height(1f); backgroundColor(0xFFEEEEEE); margin(4f, 0f, 4f, 0f) } }
        book.bids.forEachIndexed { i, (price, vol) ->
            orderBookRow("买${i + 1}", price, vol, 0xFFE53935)
        }

        book.commissionRatio?.let { ratio ->
            Text {
                attr { text("委比 ${fmt2(ratio)}%"); fontSize(12f); color(0xFF666666); marginTop(6f) }
            }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookRow(label: String, price: Double?, vol: Double?, color: Long) {
    View {
        attr { flexDirectionRow(); marginTop(3f) }
        Text { attr { text(label); fontSize(12f); color(0xFF666666); width(40f) } }
        Text { attr { text(fmtOpt(price)); fontSize(12f); color(color); flex(1f) } }
        Text { attr { text(fmtOpt(vol)); fontSize(12f); color(0xFF666666) } }
    }
}

private fun fmtOpt(v: Double?): String = if (v == null) "-" else fmt2(v)

internal fun ViewContainer<*, *>.klineChartArea(ctx: StockDetailPage) {
    val klineData = ctx.stockDetail?.kline

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
            klineChartCanvas(ctx, klineData!!)
            klineSummary(klineData)
        }
        velse {
            klineErrorView(ctx)
        }
    }
}

internal fun ViewContainer<*, *>.klineLoadingView() {
    View {
        attr {
            height(200f)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        Text {
            attr {
                text("K线数据加载中...")
                fontSize(13f)
                color(0xFF999999)
                textAlignCenter()
            }
        }
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
                color(0xFF999999)
                textAlignCenter()
            }
        }
        View {
            attr {
                marginTop(12f)
                padding(top = 8f, left = 20f, bottom = 8f, right = 20f)
                backgroundColor(0xFFE3F2FD)
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
                    color(0xFF1976D2)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.klineChartCanvas(ctx: StockDetailPage, klineData: List<KLineDataItem>) {
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
        context.fillText("成交量(手)", width - 2f, volTop - 8f)

        context.fillStyle(Color(0xFF999999))
        context.font(10f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(maxP), 4f, padT + 9f)
        context.fillText(fmt2(minP), 4f, volTop - 6f)

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
                "${k.tradeDate}  开 ${fmt2(k.open)}  收 ${fmt2(k.close)}" +
                    "  高 ${fmt2(k.high)}  低 ${fmt2(k.low)}",
                4f, 10f
            )
            context.fillStyle(tooltipColor)
            context.fillText(
                "涨跌 ${fmtSignedPct(pct)}   量 ${k.volume.toInt()} 手",
                4f, 20f
            )
        }
    }
}

internal fun ViewContainer<*, *>.klineSummary(klineData: List<KLineDataItem>) {
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
                    text("成交量: ${latest.volume.toInt()} 手")
                    fontSize(12f)
                    color(0xFF666666)
                    marginLeft(12f)
                }
            }
        }
    }
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
                }
            }

            vif({ !ctx.isAnalyzing }) {
                View {
                    attr {
                        marginLeft(8f)
                        padding(top = 4f, left = 8f, bottom = 4f, right = 8f)
                        backgroundColor(0xFFFFF9C4)
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
            analyzingView(ctx)
        }
        velseif({ ctx.aiAnalysis == null }) {
            notAnalyzedView(ctx)
        }
        velse {
            renderAnalysisBubble(ctx, ctx.aiAnalysis!!)
        }
    }
}

internal fun ViewContainer<*, *>.renderAnalysisBubble(ctx: StockDetailPage, analysis: AIAnalysisData) {
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
                backgroundColor(0xFFF1F5FF)
                borderRadius(12f)
                padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            }
            KuiklyMarkdown(content = sanitizeMarkdownForRender(text), config = chatMarkdownConfig)
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
internal fun ViewContainer<*, *>.analyzingView(ctx: StockDetailPage) {
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
                text("请稍候，当前 AI 服务正在生成分析报告")
                fontSize(12f)
                color(0xFF999999)
                marginTop(6f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.notAnalyzedView(ctx: StockDetailPage) {
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
                backgroundColor(0xFF1976D2)
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
                text("AI 将为您分析趋势、信号、风险并给出操作建议")
                fontSize(11f)
                color(0xFF999999)
                marginTop(8f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.stockDetailLoadingView() {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }

        Text {
            attr {
                text("加载中...")
                fontSize(16f)
                color(0xFF666666)
            }
        }
    }
}

internal fun ViewContainer<*, *>.errorView(ctx: StockDetailPage) {
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
                text("${ctx.loadErrorMessage}\n未找到股票 ${ctx.stockCode} 的数据")
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
                backgroundColor(0xFF1976D2)
                borderRadius(20f)
            }
            event {
                click {
                    ctx.loadStockDetail()
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

data class StockInfoData(
    val code: String,
    val name: String?,
    val industry: String?,
    val plate: String?,
    val listDate: String?
)

data class RealtimeQuoteData(
    val code: String,
    val name: String?,
    val price: Double?,
    val change: Double?,
    val changePercent: Double?,
    val openPrice: Double?,
    val preClose: Double?,
    val high: Double?,
    val low: Double?,
    val volume: Double?,
    val amount: Double?,
    val peTtm: Double?,
    val pb: Double?
)

data class IndicatorData(
    val tradeDate: String,
    val ma5: Double?, val ma10: Double?, val ma20: Double?,
    val dif: Double?, val dea: Double?, val macd: Double?,
    val rsi6: Double?,
    val kdjK: Double?, val kdjD: Double?, val kdjJ: Double?
)

data class KLineDataItem(
    val code: String,
    val tradeDate: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double?
)

data class MinutePoint(
    val time: String,
    val price: Double,
    val avgPrice: Double?,
    val volume: Double?
)

data class OrderBookData(
    val updateTime: String?,
    val bids: List<Pair<Double?, Double?>>,
    val asks: List<Pair<Double?, Double?>>,
    val commissionRatio: Double?
)

data class StockDetailData(
    val info: StockInfoData?,
    val realtime: RealtimeQuoteData?,
    val kline: List<KLineDataItem>?,
    val indicator: IndicatorData? = null
)

data class AIAnalysisData(
    val code: String,
    val name: String?,
    val analysis: Map<String, Any?>,
    val cards: List<Map<String, Any?>>
)
