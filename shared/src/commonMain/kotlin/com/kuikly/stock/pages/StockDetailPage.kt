// 组装个股行情、图表与 AI 分析页面。

package com.kuikly.stock.pages

import com.kuikly.stock.ui.component.AI_DOT_STEP_MS
import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ui.component.NumberRoll
import com.kuikly.stock.ui.component.Overlay
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
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.TextAlign
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.ui.component.PressState
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedBg
import com.kuikly.stock.ui.component.ensureSkeletonVisible
import com.kuikly.stock.ui.component.skeletonBlock
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.nowMillis
import com.kuikly.stock.data.AIVerdict
import com.kuikly.stock.ai.protocol.VerdictSynthesizer
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.network.MarketLiveProvider
import com.kuikly.stock.data.MarketRepository
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
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.openModule
import com.kuikly.stock.ui.theme.AppColor

@Page("stock_detail")
class StockDetailPage : BasePager(), KlineInteractionHost {

    internal var stockCode by observable("")
    internal var stockDetail by observable<StockDetailData?>(null)
    internal val analysisState = DetailAnalysisState("stock")
    internal var aiAnalysis: AIAnalysisData?
        get() = analysisState.analysis
        set(value) { analysisState.analysis = value }
    internal var detailScrollerRef: ViewRef<ScrollerView<*, *>>? = null
    internal var chartAnchorY = 0f
    internal var isLoading by observable(true)

    internal var refreshing by observable(false)

    internal var detailEpoch by observable(0)

    internal val quoteRoll = NumberRoll(this)

    internal var isAnalyzing by observable(false)

    internal var minuteData by observable<List<MinutePoint>?>(null)
    internal var orderBook by observable<OrderBookData?>(null)
    internal var minuteLoading by observable(false)
    internal var minuteError by observable("")
    internal var orderBookLoading by observable(false)
    internal var orderBookError by observable("")
    internal var loadErrorMessage by observable("")
    internal var dataSourceText by observable("")
    override var selectedKlineIndex by observable(-1)
    override var klineCanvasWidth by observable(0f)
    internal var watched by observable(false)
    internal var fundFlowDays by observable(5)
    internal var selectedFundDate by observable("")
    internal var industrySnapshot by observable<com.kuikly.stock.data.IndustrySnapshot?>(null)
    internal var industryExpanded by observable(false)
    internal var industryAscending by observable(false)
    internal var sectorSnapshot by observable<com.kuikly.stock.data.SectorSnapshot?>(null)
    internal var sectorExpanded by observable(false)
    internal var sectorAscending by observable(false)

    internal val press = PressState(this)

    internal val industryCardVisible: Boolean
        get() = industrySnapshot != null && sectorSnapshot == null

    internal var klinePeriod by observable("D")
    override var klineVisibleCount by observable(30)
    override var klineStartIndex by observable(0)
    internal var klineShowMA by observable(true)
    internal var klineShowVolume by observable(true)
    internal var highlightedPrice by observable(0.0)
    internal var highlightedPriceLabel by observable("")
    internal var klineInfoText by observable("")
    internal var klineToolsExpanded by observable(false)
    internal var matureChartEnabled by observable(true)
    internal val matureChartAvailable get() = pagerData.params.optBoolean("matureChart", false)
    internal var klineSubIndicator by observable("none")
    internal var klineShowTrend by observable(false)

    internal var liveStatusText by observable("")
    internal var livePaused by observable(false)
    private var liveRunning = false
    private var liveBackfilled = false

    override val crosshair = CrosshairController()
    override val nativeChartGestures: Boolean get() = pagerData.params.optBoolean("nativeChartGestures", false)
    internal var crosshairX by observable(-1f)
    internal var crosshairY by observable(-1f)
    internal var rangeStats: RangeStats? by observable(null)
    internal var isRangeSelecting by observable(false)

    internal var selectedMinuteIndex by observable(-1)
    internal var minuteCanvasWidth by observable(0f)
    internal var minuteShowAvg by observable(true)
    internal var minuteInfoText by observable("")
    internal var minuteShowVolume by observable(true)

    internal var minuteLocked by observable(false)

    internal var orderBookHighlightPrice by observable(0.0)
    internal var orderBookMode by observable("list")

    internal var aiExpandedKeys: ObservableList<String> by observableList()
    internal var aiCardHighlightKey by observable("")
    internal var reviewExpanded by observable(false)

    internal var pendingFocus: KlineFocus? by observable(null)
    internal var klineVisibleRange: Pair<String, String>? by observable(null)
    internal var verdictExpanded by observable(false)
    internal var klineSectionY by observable(0f)
    internal var aiSectionY by observable(0f)
    internal var highlightCardType: String? by observable(null)
    internal var showStickyVerdict by observable(false)
    internal val klineFocusState = KlineFocusState()
    private var focusVersion = 0

    internal val alertOverlay = Overlay(this)
    internal var pendingAlertCode by observable("")
    internal var pendingAlertName by observable("")
    internal var pendingAlertType by observable(0)
    internal var pendingAlertValue by observable(0.0)
    internal var aiErrorNotice by observable("")

    override fun didInit() {
        super.didInit()
        stockCode = pagerData.params.optString("code", "")
        analysisState.restore(stockCode)
        watched = stockCode.isNotEmpty() && WatchStore.isWatched(stockCode)

        if (stockCode.isNotEmpty()) {
            loadStockDetail()
            loadExtraQuote()
            loadDataSource()
            startLiveLoop()
        }
    }

    private fun startLiveLoop() {
        MarketRepository.liveProvider = MarketLiveProvider
        if (liveRunning) return
        liveRunning = true
        lifecycleScope.launch {
            var fails = 0
            while (liveRunning && stockCode.isNotEmpty()) {
                val codeAtStart = stockCode

                if (!liveBackfilled && !refreshing && stockDetail != null && (stockDetail?.kline?.size ?: 0) < 60) {
                    val hist = runCatching { MarketLiveProvider.dailyKline(codeAtStart, 250) }.getOrDefault(emptyList())
                    println("[Live] backfill ${codeAtStart}: ${hist.size} bars")
                    if (hist.size >= 60 && stockCode == codeAtStart) {
                        liveBackfilled = true
                        val merged = stockDetail?.copy(kline = hist)
                        if (merged != null) {
                            stockDetail = merged

                            klineVisibleCount = when (klinePeriod) {
                                "W" -> 26
                                "M" -> 12
                                else -> 30
                            }.coerceAtMost(getAggregatedKline().size)
                            klineStartIndex = (getAggregatedKline().size - klineVisibleCount).coerceAtLeast(0)
                            detailEpoch++
                        }
                    }
                }
                val quote = runCatching { MarketLiveProvider.realtimeQuote(codeAtStart) }.getOrNull()
                if (quote != null && stockCode == codeAtStart) {
                    fails = 0
                    livePaused = false
                    liveStatusText = "实时 · 已更新 ${quote.updateTime}"
                    val base = stockDetail
                    val price = quote.price
                    if (base?.realtime != null && price != null) {
                        stockDetail = base.copy(realtime = quote.copy(name = base.realtime?.name ?: base.info?.name))
                        quoteRoll.rollTo(price, quote.change ?: 0.0, quote.changePercent ?: 0.0)
                    }
                } else {
                    fails++
                    println("[Live] quote fail #$fails ${codeAtStart}")
                    if (fails >= 2 && !livePaused) {
                        livePaused = true
                        liveStatusText = "实时更新暂停 · 保留最近有效数据（${MarketLiveProvider.beijingClock()}）"
                    }
                }

                val wait = if (MarketLiveProvider.isTradingTime()) 8_000 else 60_000
                repeat(wait / 1_000) {
                    if (!liveRunning) return@launch
                    delay(1_000)
                }
            }
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()

        watched = stockCode.isNotEmpty() && WatchStore.isWatched(stockCode)
        // 返回详情页时保留图表与滚动位置，行情由实时循环继续更新。
        startLiveLoop()
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()

        liveRunning = false
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
                    backgroundColor(AppColor.SURFACE_SOFT)
                }

                vif({ ctx.isLoading }) {
                    stockDetailLoadingView(ctx)
                }
                velseif({ ctx.stockDetail == null }) {
                    errorView(ctx)
                }
                velse {
                    detailNavigationBar(ctx)

                    Scroller {
                        ref { ctx.detailScrollerRef = it }
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            scrollEnable(true)
                        }

                        // 保持滚动内容根节点稳定，数据变更只更新对应卡片。
                        View {
                            attr {
                                flexDirectionColumn()
                                width(ctx.pagerData.pageViewWidth)
                            }
                            detailContent(ctx)
                        }
                    }
                }

                vif({ ctx.alertOverlay.isVisible }) {
                    detailAlertConfirmDialog(ctx)
                }

                vif({ ctx.aiErrorNotice.isNotEmpty() }) {
                    detailAiToast(ctx)
                }
            }
        }
    }

    internal fun loadStockDetail() {
        if (stockCode.isEmpty() || refreshing) return
        val firstLoad = stockDetail == null
        refreshing = true
        if (firstLoad) {
            isLoading = true

            skeletonPulse.bump()
        }

        lifecycleScope.launch {
            val startedAt = nowMillis()
            try {
                val data = StockRepository.loadStockDetail(stockCode)
                delay(0)
                if (data != null) {
                    val history = retainLongerHistory(stockDetail?.kline.orEmpty(), data.kline.orEmpty())
                    stockDetail = data.copy(kline = history)
                    liveBackfilled = history.size >= 60
                    industrySnapshot = runCatching { com.kuikly.stock.data.StockDb.industryPeers(stockCode) }.getOrNull()
                    sectorSnapshot = runCatching { com.kuikly.stock.data.StockDb.sectorOfStock(stockCode) }.getOrNull()

                    val total = getAggregatedKline().size
                    klineVisibleCount = (when (klinePeriod) { "W" -> 26; "M" -> 12; else -> 30 }).coerceAtMost(total)
                    klineStartIndex = (total - klineVisibleCount).coerceAtLeast(0)
                    selectedKlineIndex = -1
                    klineInfoText = ""
                    publishQuote(firstLoad)
                } else {
                    loadErrorMessage = "未找到股票 $stockCode 的数据"
                    if (!firstLoad) aiErrorNotice = loadErrorMessage
                }
            } catch (e: Throwable) {
                delay(0)
                loadErrorMessage = e.message ?: "数据加载失败"
                if (!firstLoad) aiErrorNotice = loadErrorMessage
            } finally {

                if (firstLoad) ensureSkeletonVisible(startedAt)
                isLoading = false
                refreshing = false
            }
        }
    }

    private fun publishQuote(firstLoad: Boolean) {
        val rt = stockDetail?.realtime
        val price = rt?.price
        if (price == null) {
            detailEpoch++
            return
        }
        val target = doubleArrayOf(price, rt.change ?: 0.0, rt.changePercent ?: 0.0)
        if (firstLoad) quoteRoll.snap(*target)
        detailEpoch++
        if (!firstLoad) quoteRoll.rollTo(*target)
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

        aiDotWave.loop(AI_DOT_STEP_MS) { isAnalyzing }
        lifecycleScope.launch {
            try {
                val result = StockRepository.analyzeStock(stockCode)
                delay(0)
                clearHighlight()
                analysisState.accept(result)

                val levels = parseAIPriceLevels(result)
                if (levels.isNotEmpty()) {
                    highlightedPrice = levels.first().price
                    highlightedPriceLabel = levels.first().label
                }
            } catch (e: Throwable) {
                delay(0)
                analysisState.notice = "分析请求失败：${e.message ?: "网络不可用"}；已保留原分析，可重试"
                aiErrorNotice = analysisState.notice
            } finally {
                isAnalyzing = false
            }
        }
    }

    internal val effectiveVerdict: AIVerdict?
        get() = aiAnalysis?.let { com.kuikly.stock.data.AiReviewEngine.verdictOf(it) }

    internal fun focusKline(f: KlineFocus) {
        clearInteraction()
        pendingFocus = f
        klineFocusState.focus = f
        klineFocusState.focusSetAt = nowMillis()
        if (f is KlineFocus.Price) {
            highlightedPrice = f.value
            highlightedPriceLabel = f.label
        }
        when (f) {
            is KlineFocus.Point -> focusEvidenceDate(f.date)
            is KlineFocus.Range -> focusEvidenceDate(f.start)
            else -> Unit
        }
        scrollToChart()
        val version = ++focusVersion
        lifecycleScope.launch {
            delay(4000)
            if (focusVersion == version) pendingFocus = null
        }
    }

    internal fun jumpToAiSection(highlight: String? = null) {
        detailScrollerRef?.view?.setContentOffset(0f, (aiSectionY - 12f).coerceAtLeast(0f), true)
        if (highlight != null) {
            highlightCardType = highlight

            lifecycleScope.launch {
                kotlinx.coroutines.delay(1200)
                highlightCardType = null
            }
        }
    }

    internal fun loadExtraQuote() { loadMinuteQuote(); loadBookQuote() }

    internal fun loadMinuteQuote() {
        if (stockCode.isEmpty() || minuteLoading) return
        minuteLoading = true
        minuteError = ""
        skeletonPulse.bump()
        lifecycleScope.launch {
            val startedAt = nowMillis()
            try {
                val data = StockRepository.loadMinute(stockCode)
                delay(0)
                minuteData = data ?: emptyList()
                clearMinuteSelection()
            } catch (e: Throwable) {
                delay(0)
                minuteData = emptyList()
                minuteError = e.message ?: "分时加载失败"
            } finally {
                ensureSkeletonVisible(startedAt)
                minuteLoading = false
            }
        }
    }

    internal fun loadBookQuote() {
        if (stockCode.isEmpty() || orderBookLoading) return
        orderBookLoading = true
        orderBookError = ""
        skeletonPulse.bump()
        lifecycleScope.launch {
            val startedAt = nowMillis()
            try {
                val data = StockRepository.loadOrderBook(stockCode)
                delay(0)
                orderBook = data?.takeIf { book -> (book.bids + book.asks).any { (price, _) -> price != null && price.isFinite() && price > 0 } }
            } catch (e: Throwable) {
                delay(0)
                orderBook = null
                orderBookError = e.message ?: "盘口加载失败"
            } finally {
                ensureSkeletonVisible(startedAt)
                orderBookLoading = false
            }
        }
    }

    internal fun switchKlinePeriod(period: String) {
        if (klinePeriod == period) return
        clearInteraction()
        klinePeriod = period

        val aggregated = getAggregatedKline()
        val total = aggregated.size
        klineVisibleCount = when (period) {
            "W" -> 26.coerceAtMost(total)
            "M" -> 12.coerceAtMost(total)
            else -> 30.coerceAtMost(total)
        }
        klineStartIndex = (total - klineVisibleCount).coerceAtLeast(0)
        selectedKlineIndex = -1
        klineInfoText = if (period == "D") "日K" else if (period == "W") "周K · 自然周聚合" else "月K · 按月聚合"
    }

    override fun getAggregatedKline(): List<KLineDataItem> {
        val original = stockDetail?.kline ?: return emptyList()

        if (aggCacheSource === original && aggCachePeriod == klinePeriod) return aggCacheValue
        val agg = when (klinePeriod) {
            "W" -> aggregateToWeekly(original)
            "M" -> aggregateToMonthly(original)
            else -> original
        }
        aggCacheSource = original
        aggCachePeriod = klinePeriod
        aggCacheValue = agg
        return agg
    }

    private var aggCacheSource: List<KLineDataItem>? = null
    private var aggCachePeriod: String? = null
    private var aggCacheValue: List<KLineDataItem> = emptyList()

    private var indCacheSource: List<KLineDataItem>? = null
    private var indCacheMacd: MacdSeries? = null
    private var indCacheKdj: KdjSeries? = null
    private var indCacheRsi: RsiSeries? = null

    private fun resetIndicatorCacheIfNeeded(agg: List<KLineDataItem>) {
        if (indCacheSource !== agg) {
            indCacheSource = agg
            indCacheMacd = null
            indCacheKdj = null
            indCacheRsi = null
        }
    }

    internal fun macdOf(agg: List<KLineDataItem>): MacdSeries {
        resetIndicatorCacheIfNeeded(agg)
        return indCacheMacd ?: computeMACD(agg.map { it.close }).also { indCacheMacd = it }
    }

    internal fun kdjOf(agg: List<KLineDataItem>): KdjSeries {
        resetIndicatorCacheIfNeeded(agg)
        return indCacheKdj ?: computeKDJ(
            agg.map { it.high }, agg.map { it.low }, agg.map { it.close }
        ).also { indCacheKdj = it }
    }

    internal fun rsiOf(agg: List<KLineDataItem>): RsiSeries {
        resetIndicatorCacheIfNeeded(agg)
        return indCacheRsi ?: computeRSI(agg.map { it.close }).also { indCacheRsi = it }
    }

    private var trendCacheSource: List<KLineDataItem>? = null
    private var trendCacheStart = -1
    private var trendCacheCount = -1
    private var trendCacheValue: TrendLines = TrendLines(null, null)

    internal fun trendLinesOf(
        agg: List<KLineDataItem>,
        start: Int,
        count: Int,
        visible: List<KLineDataItem>,
    ): TrendLines {
        if (visible.size < 5) return TrendLines(null, null)
        if (trendCacheSource === agg && trendCacheStart == start && trendCacheCount == count) {
            return trendCacheValue
        }
        val lines = computeTrendlines(visible.map { it.high }, visible.map { it.low })
        trendCacheSource = agg
        trendCacheStart = start
        trendCacheCount = count
        trendCacheValue = lines
        return lines
    }

    private var aiLevelsSource: AIAnalysisData? = null
    private var aiLevelsResolved = false
    private var aiLevelsCache: List<AIPriceLevel> = emptyList()

    internal fun aiPriceLevels(): List<AIPriceLevel> {
        val analysis = aiAnalysis
        if (aiLevelsResolved && aiLevelsSource === analysis) return aiLevelsCache
        aiLevelsCache = parseAIPriceLevels(analysis)
        aiLevelsSource = analysis
        aiLevelsResolved = true
        return aiLevelsCache
    }

    internal fun getVisibleKline(): List<KLineDataItem> {
        val agg = getAggregatedKline()
        val window = klineViewport(agg.size, klineStartIndex, klineVisibleCount)
        return if (window.isEmpty()) emptyList() else agg.subList(window.first, window.last + 1)
    }

    private var chartAnimSeq = 0

    internal fun zoomIn() = animateZoom(-5)

    internal fun zoomOut() = animateZoom(+5)

    internal fun panLeft() = animatePan(-5)

    internal fun panRight() = animatePan(+5)

    private fun animateZoom(delta: Int) {
        clearChartSelection()
        val total = getAggregatedKline().size
        if (total <= 0) return
        val newCount = (klineVisibleCount + delta).coerceAtLeast(10).coerceAtMost(90).coerceAtMost(total)
        if (newCount == klineVisibleCount) return
        val center = klineStartIndex + klineVisibleCount / 2f
        val newStart = (center - newCount / 2f).coerceIn(0f, (total - newCount).coerceAtLeast(0).toFloat())
        animateViewport(newStart, newCount.toFloat())
    }

    private fun animatePan(delta: Int) {
        clearChartSelection()
        val total = getAggregatedKline().size
        val maxStart = (total - klineVisibleCount).coerceAtLeast(0)
        val newStart = (klineStartIndex + delta).coerceIn(0, maxStart)
        animateViewport(newStart.toFloat(), klineVisibleCount.toFloat())
    }

    private fun animateViewport(toStart: Float, toCount: Float) {
        val seq = ++chartAnimSeq
        tweenChartViewport(
            fromStart = klineStartIndex.toFloat(),
            fromCount = klineVisibleCount.toFloat(),
            toStart = toStart,
            toCount = toCount,
            isCancelled = { seq != chartAnimSeq },
        ) { start, count ->
            klineStartIndex = start
            klineVisibleCount = count
        }
    }

    internal fun zoomAtX(anchorX: Float, factor: Float) {
        clearChartSelection()
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val mapper = ChartCoordinateMapper(
            visibleStartIdx = klineStartIndex,
            visibleCount = klineVisibleCount,
            priceMin = 0.0, priceMax = 1.0,
            chartWidth = klineCanvasWidth, chartTop = 0f, chartHeight = 0f,
        )
        val (newStart, newCount) = mapper.zoomAt(anchorX, factor, agg.size)
        klineVisibleCount = newCount
        klineStartIndex = newStart
    }

    internal fun panByPixel(deltaX: Float) {
        if (klineCanvasWidth <= 0f || klineVisibleCount <= 0) return
        val deltaIdx = (deltaX / klineCanvasWidth * klineVisibleCount).toInt()
        if (deltaIdx != 0) {
            val total = getAggregatedKline().size
            klineStartIndex = (klineStartIndex - deltaIdx).coerceIn(0, (total - klineVisibleCount).coerceAtLeast(0))
        }
    }

    override fun updateCrosshair(x: Float, y: Float) {
        crosshairX = x
        crosshairY = y
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onMove(globalIdx)
        val k = agg.getOrNull(globalIdx)
        if (k != null) {
            val changePct = if (k.open != 0.0) (k.close - k.open) / k.open * 100.0 else 0.0
            klineInfoText = "${k.tradeDate} 开${fmt2(k.open)} 收${fmt2(k.close)} 高${fmt2(k.high)} 低${fmt2(k.low)} ${fmtSignedPct(changePct)}"
        }
    }

    override fun tapCrosshair(x: Float) {
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onTap(globalIdx)
        crosshairY = -1f
        selectedKlineIndex = if (crosshair.state is InteractionState.Locked) globalIdx else -1
        crosshairX = if (crosshair.state is InteractionState.Locked) x else -1f
        if (crosshair.state !is InteractionState.Locked) {
            klineInfoText = ""
            rangeStats = null
        }
    }

    override fun beginRangeSelect(x: Float) {
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onRangeStart(globalIdx)
        isRangeSelecting = true
        crosshairX = x
    }

    override fun updateRangeSelect(x: Float) {
        if (!isRangeSelecting) return
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onRangeUpdate(globalIdx)
        crosshairX = x
        val rs = crosshair.state
        if (rs is InteractionState.RangeSelect) {
            rangeStats = summarizeRange(agg, rs.startGlobalIdx, rs.endGlobalIdx)
        }
    }

    override fun endRangeSelect() {
        crosshair.onRangeEnd()
        isRangeSelecting = false
        if (crosshair.state is InteractionState.Locked) {
            val idx = (crosshair.state as InteractionState.Locked).globalIdx
            selectedKlineIndex = idx
        }
    }

    override fun clearInteraction() {
        crosshair.reset()
        crosshairX = -1f
        crosshairY = -1f
        rangeStats = null
        isRangeSelecting = false
        selectedKlineIndex = -1
        klineInfoText = ""
    }

    internal fun resetView() {
        clearChartSelection()
        val total = getAggregatedKline().size
        if (total <= 0) return
        val defaultCount = when (klinePeriod) {
            "W" -> 26
            "M" -> 12
            else -> 30
        }.coerceAtMost(total)
        animateViewport(
            toStart = (total - defaultCount).coerceAtLeast(0).toFloat(),
            toCount = defaultCount.toFloat(),
        )
        selectedKlineIndex = -1
        highlightedPrice = 0.0
        highlightedPriceLabel = ""
    }

    internal var chartResetTick by observable(0)

    internal fun resetKline() {
        if (matureChartAvailable && matureChartEnabled) {
            chartResetTick++
        } else {
            resetView()
        }
    }

    internal fun toggleMA() {
        klineShowMA = !klineShowMA
    }

    internal fun toggleVolume() {
        klineShowVolume = !klineShowVolume
    }

    internal fun toggleTrend() {
        klineShowTrend = !klineShowTrend
    }

    internal fun selectKlineAtX(x: Float) {
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val visibleCount = getVisibleKline().size
        if (visibleCount <= 0) return
        val visibleIndex = chartHitIndex(x, klineCanvasWidth, visibleCount)
        val globalIndex = (klineStartIndex + visibleIndex).coerceIn(0, agg.size - 1)
        selectedKlineIndex = globalIndex

        val k = agg.getOrNull(globalIndex)
        if (k != null) {
            val changePct = if (k.open != 0.0) (k.close - k.open) / k.open * 100.0 else 0.0
            klineInfoText = "${k.tradeDate} 开${fmt2(k.open)} 收${fmt2(k.close)} 高${fmt2(k.high)} 低${fmt2(k.low)} ${fmtSignedPct(changePct)}"
        }
    }

    override fun clearChartSelection() {
        clearInteraction()
        selectedKlineIndex = -1
        klineInfoText = ""
    }

    internal fun scrollToChart() {
        detailScrollerRef?.view?.setContentOffset(offsetX = 0f, offsetY = chartAnchorY.coerceAtLeast(0f), animated = true)
    }

    internal fun focusCandle(index: Int) {
        val bars = getAggregatedKline()
        if (index !in bars.indices) return
        klineVisibleCount = klineVisibleCount.coerceAtLeast(1).coerceAtMost(bars.size)
        klineStartIndex = (index - klineVisibleCount / 2).coerceIn(0, (bars.size - klineVisibleCount).coerceAtLeast(0))
        selectedKlineIndex = index
        crosshair.reset()
        crosshair.onTap(index)
        crosshairY = -1f
        crosshairX = ((index - klineStartIndex + 0.5f) / klineVisibleCount) * klineCanvasWidth
        klineInfoText = candleEvidence(bars, index)
        klineShowVolume = true
        scrollToChart()
    }

    internal fun focusEvidenceDate(date: String) {
        switchKlinePeriod("D")
        val index = getAggregatedKline().indexOfFirst { normalizedTradeDate(it.tradeDate) == normalizedTradeDate(date) }
        if (index < 0) { analysisState.notice = "当前行情中没有 $date 的K线，请核对分析日期"; return }
        focusCandle(index)
    }

    internal fun askAboutChart(indicator: String = klineSubIndicator) {
        val selected = selectedKlineIndex
        val bars = if (selected >= 0) getAggregatedKline() else getVisibleKline()
        val prompt = detailFollowupPrompt("stock", stockCode, stockDetail?.info?.name ?: stockCode, klinePeriod, bars, selected, aiAnalysis,
            range = rangeStats, viewport = getVisibleKline(), indicator = indicator)
        val params = JSONObject()
        params.put("detail_question", prompt)
        openModule(AppRoutes.CHAT, params)
    }

    internal fun highlightAIPrice(price: Double, label: String) {
        if (price <= 0 || !price.isFinite()) return
        highlightedPrice = price
        highlightedPriceLabel = label
        scrollToChart()

        aiErrorNotice = "已在K线标注 $label ¥${fmt2(price)}"
        lifecycleScope.launch {
            delay(2000)
            if (aiErrorNotice.contains("已在K线标注")) aiErrorNotice = ""
        }
    }

    internal fun clearHighlight() {
        highlightedPrice = 0.0
        highlightedPriceLabel = ""
        orderBookHighlightPrice = 0.0
    }

    override fun selectMinuteAtX(x: Float, locked: Boolean) {
        val data = minuteData ?: return
        if (data.isEmpty() || minuteCanvasWidth <= 0f) return
        val idx = ((x / minuteCanvasWidth) * data.size).toInt().coerceIn(0, data.size - 1)

        if (locked && minuteLocked && idx == selectedMinuteIndex) {
            clearMinuteSelection()
            return
        }
        minuteLocked = locked
        selectedMinuteIndex = idx
        val point = data.getOrNull(idx)
        if (point != null) {
            val avgText = point.avgPrice?.let { " 均${fmt2(it)}" } ?: ""
            val volText = point.volume?.let { " 量${it.toInt()}" } ?: ""
            minuteInfoText = "${point.time} 价${fmt2(point.price)}$avgText$volText"

            highlightedPrice = point.price
            highlightedPriceLabel = "分时 ${point.time}"
        }
    }

    internal fun toggleMinuteAvg() {
        minuteShowAvg = !minuteShowAvg
    }

    internal fun toggleMinuteVolume() {
        minuteShowVolume = !minuteShowVolume
    }

    override fun clearMinuteSelection() {
        if (highlightedPriceLabel.startsWith("分时 ")) clearHighlight()
        selectedMinuteIndex = -1
        minuteInfoText = ""
        minuteLocked = false
    }

    internal fun highlightOrderBookPrice(price: Double, label: String) {
        if (price <= 0 || !price.isFinite()) return
        orderBookHighlightPrice = price
        highlightedPrice = price
        highlightedPriceLabel = "盘口 $label"
        aiErrorNotice = "已在K线标注 $label ¥${fmt2(price)}"
        lifecycleScope.launch {
            delay(2000)
            if (aiErrorNotice.contains("已在K线标注")) aiErrorNotice = ""
        }
    }

    internal fun toggleOrderBookMode() {
        orderBookMode = if (orderBookMode == "list") "depth" else "list"
    }

    internal fun prepareAlertFromOrderBook(price: Double) {
        prepareAlertFromDetail(0, price)
    }

    internal fun toggleAISection(key: String) {
        val cur = aiExpandedKeys.toList()
        aiExpandedKeys.clear()
        if (cur.contains(key)) {
            aiExpandedKeys.addAll(cur.filter { it != key })
        } else {
            aiExpandedKeys.addAll(cur + key)
        }
    }

    internal fun isAIExpanded(key: String): Boolean = aiExpandedKeys.contains(key)

    internal fun prepareAlertFromDetail(type: Int, value: Double) {
        if (stockCode.isBlank() || !value.isFinite() || value <= 0.0) {
            aiErrorNotice = "当前没有可用的精确价位"
            lifecycleScope.launch {
                delay(2000)
                aiErrorNotice = ""
            }
            return
        }
        val name = stockDetail?.info?.name ?: stockCode
        pendingAlertCode = stockCode
        pendingAlertName = name
        pendingAlertType = type
        pendingAlertValue = value
        alertOverlay.show()
    }

    internal fun dismissAlertConfirm() {
        alertOverlay.hide()
    }

    internal fun confirmAlert() {
        val rule = if (pendingAlertType == 1) {
            ConclusionAlertFactory.support(pendingAlertCode, pendingAlertName, pendingAlertValue)
        } else {
            ConclusionAlertFactory.resistance(pendingAlertCode, pendingAlertName, pendingAlertValue)
        }
        if (!WatchStore.upsertAlert(rule)) {
            aiErrorNotice = "提醒保存失败，请重试"
            return
        }
        watched = WatchStore.isWatched(stockCode)
        dismissAlertConfirm()
        aiErrorNotice = "提醒已创建 · ${pendingAlertName} ${if (pendingAlertType == 1) "跌至" else "涨至"} ${fmt2(pendingAlertValue)}"
        lifecycleScope.launch {
            delay(2500)
            aiErrorNotice = ""
        }
    }
}

private fun ViewContainer<*, *>.detailContent(ctx: StockDetailPage) {
    // 注意：下面这几处必须用 listOfNotNull(...)，数据缺失时列表为空、creator 不执行。
    // 若写成 mutableListOf(可能为 null 的值)，creator 仍会被调用一次，而卡片函数在数据为 null
    // 时是 `?: return`，会产出 0 个孩子节点，框架随即抛
    // 「vfor creator闭包内必须需要且仅一个孩子节点的生成」并崩溃。
    vfor({ ObservableList(listOfNotNull(ctx.stockDetail?.realtime).toMutableList()) }) { _ -> realtimeCard(ctx) }
    industryCard(ctx)
    sectorCard(ctx)
    vfor({ ObservableList(mutableListOf(ctx.detailEpoch)) }) { _ -> eventCard(ctx) }
    View {
        event { layoutFrameDidChange { frame -> ctx.chartAnchorY = frame.y } }
        klineChartArea(ctx)
    }
    minuteCard(ctx)
    orderBookCard(ctx)
    vfor({ ObservableList(listOfNotNull(ctx.stockDetail?.indicator).toMutableList()) }) { _ -> indicatorCard(ctx) }
    vfor({ ObservableList(mutableListOf(ctx.stockDetail?.fundFlow)) }) { _ -> fundFlowCard(ctx) }
    View {
        event { layoutFrameDidChange { frame -> ctx.aiSectionY = frame.y } }
        aiAnalysisCards(ctx)
    }
    aiReviewCard(ctx) { record ->
        ctx.clearInteraction()
        ctx.clearChartSelection()
        ctx.clearMinuteSelection()
        ctx.clearHighlight()
        ctx.aiExpandedKeys.clear()
        ctx.analysisState.select(record)
        ctx.analysisState.notice = "已切换到该历史分析，已滚动至底部查看"
        ctx.jumpToAiSection()
    }
    analysisHistoryPanel(ctx.analysisState) {
        ctx.clearInteraction()
        ctx.clearChartSelection()
        ctx.clearMinuteSelection()
        ctx.clearHighlight()
        ctx.aiExpandedKeys.clear()
        ctx.jumpToAiSection()
    }
    vfor({ ObservableList(listOfNotNull(ctx.stockDetail?.info).toMutableList()) }) { _ -> infoCard(ctx) }
    vif({ ctx.dataSourceText.isNotEmpty() }) {
        dataSourceFooter(ctx)
    }
}
