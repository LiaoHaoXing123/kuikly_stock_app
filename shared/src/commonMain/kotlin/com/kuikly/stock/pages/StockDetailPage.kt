// 个股详情页：基础信息、实时行情、技术指标、分时与五档盘口，以及 AI 分析入口。
// 增强版：K线多周期/缩放/平移/MA/AI价位联动，AI分析卡片化与K线深度融合

package com.kuikly.stock.pages

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
import com.kuikly.stock.data.AIVerdict
import com.kuikly.stock.ai.protocol.VerdictSynthesizer
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.data.ConclusionAlertFactory
import com.kuikly.stock.data.PriceAlertRule
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuiklybase.KuiklyMarkdown
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmt3
import com.kuikly.stock.data.fmtSigned2
import com.kuikly.stock.data.fmtSignedPct
import kotlin.math.abs

@Page("stock_detail")
class StockDetailPage : Pager() {

    internal var stockCode by observable("")
    internal var stockDetail by observable<StockDetailData?>(null)
    internal val analysisState = DetailAnalysisState("stock")
    internal var aiAnalysis: AIAnalysisData?
        get() = analysisState.analysis
        set(value) { analysisState.analysis = value }
    internal var detailScrollerRef: ViewRef<ScrollerView<*, *>>? = null
    internal var chartAnchorY = 0f
    internal var isLoading by observable(true)
    internal var isAnalyzing by observable(false)
    internal var minuteData by observable<List<MinutePoint>?>(null)
    internal var orderBook by observable<OrderBookData?>(null)
    internal var minuteLoading by observable(false)
    internal var minuteError by observable("")
    internal var orderBookLoading by observable(false)
    internal var orderBookError by observable("")
    internal var loadErrorMessage by observable("")
    internal var dataSourceText by observable("")
    internal var selectedKlineIndex by observable(-1)
    internal var klineCanvasWidth by observable(0f)
    internal var watched by observable(false)

    // --- K线增强状态 ---
    internal var klinePeriod by observable("D") // D=日 W=周 M=月
    internal var klineVisibleCount by observable(30)
    internal var klineStartIndex by observable(0)
    internal var klineShowMA by observable(true)
    internal var klineShowVolume by observable(true)
    internal var highlightedPrice by observable(0.0)
    internal var highlightedPriceLabel by observable("")
    internal var klineInfoText by observable("")
    internal var klineSubIndicator by observable("none") // none / macd / kdj，副图指标，默认关闭

    // --- P1 K线交互状态 ---
    internal val crosshair = CrosshairController()
    internal var crosshairX by observable(-1f)  // 当前十字光标 x 像素（-1=不显示）
    internal var crosshairY by observable(-1f)  // 当前十字光标 y 像素
    internal var rangeStats: RangeStats? by observable(null)  // 区间统计结果
    internal var isRangeSelecting by observable(false)  // 是否正在框选区间

    // --- 分时增强 ---
    internal var selectedMinuteIndex by observable(-1)
    internal var minuteCanvasWidth by observable(0f)
    internal var minuteShowAvg by observable(true)
    internal var minuteInfoText by observable("")
    internal var minuteShowVolume by observable(true)

    // --- 盘口增强 ---
    internal var orderBookHighlightPrice by observable(0.0)
    internal var orderBookMode by observable("list") // list / depth

    // --- AI卡片交互 ---
    internal var aiExpandedKeys: ObservableList<String> by observableList()
    internal var aiCardHighlightKey by observable("")

    // --- P0 AI联动状态 ---
    internal var pendingFocus: KlineFocus? by observable(null)
    internal var klineVisibleRange: Pair<String, String>? by observable(null)
    internal var verdictExpanded by observable(false)
    internal var klineSectionY by observable(0f)
    internal var aiSectionY by observable(0f)
    internal var highlightCardType: String? by observable(null)
    internal var showStickyVerdict by observable(false)
    internal val klineFocusState = KlineFocusState()

    // --- 提醒确认弹窗 ---
    internal var showAlertConfirm by observable(false)
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
                        ref { ctx.detailScrollerRef = it }
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            scrollEnable(true)
                        }

                        analysisHistoryPanel(ctx.analysisState) { ctx.clearChartSelection(); ctx.clearMinuteSelection(); ctx.clearHighlight(); ctx.aiExpandedKeys.clear() }
                        infoCard(ctx)
                        realtimeCard(ctx)
                        indicatorCard(ctx)
                        View {
                            event { layoutFrameDidChange { frame -> ctx.chartAnchorY = frame.y } }
                            klineChartArea(ctx)
                        }
                        minuteCard(ctx)
                        orderBookCard(ctx)
                        View {
                            event { layoutFrameDidChange { frame -> ctx.aiSectionY = frame.y } }
                            aiAnalysisCards(ctx)
                        }

                        vif({ ctx.dataSourceText.isNotEmpty() }) {
                            dataSourceFooter(ctx)
                        }
                    }
                }

                vif({ ctx.showAlertConfirm }) {
                    detailAlertConfirmDialog(ctx)
                }

                vif({ ctx.aiErrorNotice.isNotEmpty() }) {
                    detailAiToast(ctx)
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
                    // 初始化K线视口
                    val total = data.kline?.size ?: 0
                    klineVisibleCount = when {
                        total >= 60 -> 30
                        total >= 30 -> total
                        else -> total
                    }
                    klineStartIndex = (total - klineVisibleCount).coerceAtLeast(0)
                    selectedKlineIndex = -1
                    klineInfoText = ""
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
                clearHighlight()
                analysisState.accept(result)
                // 自动高亮第一个关键价位
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

    /** 统一：无论 v2/v1/离线/旧历史记录，UI 永远拿得到一个 verdict */
    internal val effectiveVerdict: AIVerdict?
        get() {
            val d = aiAnalysis ?: return null
            d.verdict?.let { return it }
            return AIVerdict.fromSynth(
                VerdictSynthesizer.fromLegacy(d.analysis) { DeepSeekApi.numericLevel(it) }
            )
        }

    /** 统一联动入口：滚至 K 线并高亮目标 */
    internal fun focusKline(f: KlineFocus) {
        pendingFocus = f
        klineFocusState.focus = f
        klineFocusState.focusSetAt = System.currentTimeMillis()
        detailScrollerRef?.view?.setContentOffset(0f, (klineSectionY - 12f).coerceAtLeast(0f), true)
    }

    internal fun jumpToAiSection(highlight: String? = null) {
        detailScrollerRef?.view?.setContentOffset(0f, (aiSectionY - 12f).coerceAtLeast(0f), true)
        if (highlight != null) {
            highlightCardType = highlight
            // 1.2秒后清除高亮
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
        lifecycleScope.launch {
            try {
                val data = StockRepository.loadMinute(stockCode)
                delay(0)
                minuteData = data ?: emptyList()
                clearMinuteSelection()
            } catch (e: Throwable) {
                delay(0)
                minuteData = emptyList()
                minuteError = e.message ?: "分时加载失败"
            } finally { minuteLoading = false }
        }
    }

    internal fun loadBookQuote() {
        if (stockCode.isEmpty() || orderBookLoading) return
        orderBookLoading = true
        orderBookError = ""
        lifecycleScope.launch {
            try {
                val data = StockRepository.loadOrderBook(stockCode)
                delay(0)
                orderBook = data?.takeIf { book -> (book.bids + book.asks).any { (price, _) -> price != null && price.isFinite() && price > 0 } }
            } catch (e: Throwable) {
                delay(0)
                orderBook = null
                orderBookError = e.message ?: "盘口加载失败"
            } finally { orderBookLoading = false }
        }
    }

    // --- K线周期与视口逻辑 ---

    internal fun switchKlinePeriod(period: String) {
        if (klinePeriod == period) return
        klinePeriod = period
        // 切换周期后重置视口
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

    internal fun getAggregatedKline(): List<KLineDataItem> {
        val original = stockDetail?.kline ?: return emptyList()
        return when (klinePeriod) {
            "W" -> aggregateToWeekly(original)
            "M" -> aggregateToMonthly(original)
            else -> original
        }
    }

    internal fun getVisibleKline(): List<KLineDataItem> {
        val agg = getAggregatedKline()
        if (agg.isEmpty()) return emptyList()
        val start = klineStartIndex.coerceIn(0, (agg.size - 1).coerceAtLeast(0))
        val end = (start + klineVisibleCount).coerceAtMost(agg.size)
        return if (start >= end) emptyList() else agg.subList(start, end)
    }

    internal fun zoomIn() {
        clearChartSelection()
        val newCount = (klineVisibleCount - 5).coerceAtLeast(10).coerceAtMost(getAggregatedKline().size)
        if (newCount != klineVisibleCount) {
            // 保持中心点
            val center = klineStartIndex + klineVisibleCount / 2
            klineVisibleCount = newCount
            klineStartIndex = (center - newCount / 2).coerceIn(0, (getAggregatedKline().size - newCount).coerceAtLeast(0))
        }
    }

    internal fun zoomOut() {
        clearChartSelection()
        val total = getAggregatedKline().size
        val newCount = (klineVisibleCount + 5).coerceAtMost(90).coerceAtMost(total)
        if (newCount != klineVisibleCount) {
            val center = klineStartIndex + klineVisibleCount / 2
            klineVisibleCount = newCount
            klineStartIndex = (center - newCount / 2).coerceIn(0, (total - newCount).coerceAtLeast(0))
        }
    }

    internal fun panLeft() {
        clearChartSelection()
        klineStartIndex = (klineStartIndex - 5).coerceAtLeast(0)
    }

    internal fun panRight() {
        clearChartSelection()
        val total = getAggregatedKline().size
        klineStartIndex = (klineStartIndex + 5).coerceAtMost((total - klineVisibleCount).coerceAtLeast(0))
    }

    /** P1: 以指定 x 像素为锚点缩放 */
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

    /** P1: 拖拽平移，deltaX 为像素位移 */
    internal fun panByPixel(deltaX: Float) {
        if (klineCanvasWidth <= 0f || klineVisibleCount <= 0) return
        val deltaIdx = (deltaX / klineCanvasWidth * klineVisibleCount).toInt()
        if (deltaIdx != 0) {
            val total = getAggregatedKline().size
            klineStartIndex = (klineStartIndex - deltaIdx).coerceIn(0, (total - klineVisibleCount).coerceAtLeast(0))
        }
    }

    /** P1: 更新十字光标位置并计算统计 */
    internal fun updateCrosshair(x: Float, y: Float) {
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

    /** P1: 点击十字光标（锁定/解锁） */
    internal fun tapCrosshair(x: Float) {
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onTap(globalIdx)
        selectedKlineIndex = if (crosshair.state is InteractionState.Locked) globalIdx else -1
        crosshairX = if (crosshair.state is InteractionState.Locked) x else -1f
        if (crosshair.state !is InteractionState.Locked) {
            klineInfoText = ""
            rangeStats = null
        }
    }

    /** P1: 开始区间选择 */
    internal fun beginRangeSelect(x: Float) {
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onRangeStart(globalIdx)
        isRangeSelecting = true
        crosshairX = x
    }

    /** P1: 更新区间选择 */
    internal fun updateRangeSelect(x: Float) {
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

    /** P1: 结束区间选择 */
    internal fun endRangeSelect() {
        crosshair.onRangeEnd()
        isRangeSelecting = false
        if (crosshair.state is InteractionState.Locked) {
            val idx = (crosshair.state as InteractionState.Locked).globalIdx
            selectedKlineIndex = idx
        }
    }

    /** P1: 清除所有交互状态 */
    internal fun clearInteraction() {
        crosshair.reset()
        crosshairX = -1f
        crosshairY = -1f
        rangeStats = null
        isRangeSelecting = false
        clearChartSelection()
    }

    internal fun resetView() {
        clearChartSelection()
        val total = getAggregatedKline().size
        klineStartIndex = (total - klineVisibleCount).coerceAtLeast(0)
        selectedKlineIndex = -1
        highlightedPrice = 0.0
        highlightedPriceLabel = ""
    }

    internal fun toggleMA() {
        klineShowMA = !klineShowMA
    }

    internal fun toggleVolume() {
        klineShowVolume = !klineShowVolume
    }

    internal fun selectKlineAtX(x: Float) {
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val visibleCount = getVisibleKline().size
        if (visibleCount <= 0) return
        val visibleIndex = chartHitIndex(x, klineCanvasWidth, visibleCount)
        val globalIndex = (klineStartIndex + visibleIndex).coerceIn(0, agg.size - 1)
        selectedKlineIndex = globalIndex
        // 更新信息文本
        val k = agg.getOrNull(globalIndex)
        if (k != null) {
            val changePct = if (k.open != 0.0) (k.close - k.open) / k.open * 100.0 else 0.0
            klineInfoText = "${k.tradeDate} 开${fmt2(k.open)} 收${fmt2(k.close)} 高${fmt2(k.high)} 低${fmt2(k.low)} ${fmtSignedPct(changePct)}"
        }
    }

    internal fun clearChartSelection() {
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

    internal fun askAboutChart() {
        val selected = selectedKlineIndex
        val bars = if (selected >= 0) getAggregatedKline() else getVisibleKline()
        val prompt = detailFollowupPrompt("stock", stockCode, stockDetail?.info?.name ?: stockCode, klinePeriod, bars, selected, aiAnalysis)
        val params = JSONObject()
        params.put("detail_question", prompt)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("chat_main", params)
    }

    internal fun highlightAIPrice(price: Double, label: String) {
        if (price <= 0 || !price.isFinite()) return
        highlightedPrice = price
        highlightedPriceLabel = label
        scrollToChart()
        // 轻提示
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

    // 分时交互
    internal fun selectMinuteAtX(x: Float) {
        val data = minuteData ?: return
        if (data.isEmpty() || minuteCanvasWidth <= 0f) return
        val idx = ((x / minuteCanvasWidth) * data.size).toInt().coerceIn(0, data.size - 1)
        selectedMinuteIndex = idx
        val point = data.getOrNull(idx)
        if (point != null) {
            val avgText = point.avgPrice?.let { " 均${fmt2(it)}" } ?: ""
            val volText = point.volume?.let { " 量${it.toInt()}" } ?: ""
            minuteInfoText = "${point.time} 价${fmt2(point.price)}$avgText$volText"
            // 联动到K线：把分时价标到K线
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

    internal fun clearMinuteSelection() {
        if (highlightedPriceLabel.startsWith("分时 ")) clearHighlight()
        selectedMinuteIndex = -1
        minuteInfoText = ""
    }

    // 盘口交互
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

    // AI卡片展开/收起
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

    // 提醒
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
        showAlertConfirm = true
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
        showAlertConfirm = false
        aiErrorNotice = "提醒已创建 · ${pendingAlertName} ${if (pendingAlertType == 1) "跌至" else "涨至"} ${fmt2(pendingAlertValue)}"
        lifecycleScope.launch {
            delay(2500)
            aiErrorNotice = ""
        }
    }
}

// --- 聚合逻辑 ---

// --- AI价位解析 ---

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
    val v = ctx.effectiveVerdict
    val loading = ctx.isAnalyzing

    View {
        attr {
            marginLeft(8f)
            height(20f)
            borderRadius(10f)
            allCenter()
            paddingLeft(7f)
            paddingRight(7f)
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
                if (v == null) ctx.triggerAIAnalysis() else ctx.jumpToAiSection("level_card")
            }
        }

        Text {
            attr {
                text(
                    when {
                        loading -> "AI 研判中…"
                        v != null -> "AI ${v.bias}"
                        else -> "⟡ AI 研判"
                    }
                )
                fontSize(10f)
                fontWeightBold()
                color(v?.colorValue ?: 0xFF6B7280)
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
            height(if (expanded) 100f else 34f)
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
        if (expanded && v != null) {
            View {
                attr { flexDirectionRow(); height(30f); alignItemsCenter(); marginTop(4f) }
                v.supportValue?.let { p ->
                    pricePill("支撑", p, 0xFF17A67A) { ctx.focusKline(KlineFocus.Price(p, "AI支撑", 0xFF17A67A)) }
                }
                v.resistanceValue?.let { p ->
                    pricePill("压力", p, 0xFFE64545) { ctx.focusKline(KlineFocus.Price(p, "AI压力", 0xFFE64545)) }
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

// --- UI组件 ---

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
            attr { padding(10f, 10f, 6f, 10f) }
            event { click { ctx.toggleWatch() } }
            Text {
                attr {
                    text(if (ctx.watched) "★" else "☆")
                    fontSize(22f)
                    color(0xFFFFFFFF)
                }
            }
        }

        View {
            attr {
                padding(10f, 12f, 10f, 12f)
                backgroundColor(if (ctx.isAnalyzing) 0xFF4A90D9 else 0x00FFFFFF)
                borderRadius(8f)
            }
            event { click { ctx.triggerAIAnalysis() } }
            Text {
                attr {
                    text(if (ctx.isAnalyzing) "分析中…" else "AI分析")
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
            aiBiasChip(ctx)
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

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            Text {
                attr {
                    text("技术指标（${ind.tradeDate}）")
                    fontSize(15f)
                    fontWeightBold()
                    color(0xFF333333)
                    flex(1f)
                }
            }
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(if (ctx.klineShowMA) 0xFFE3F2FD else 0xFFF5F5F5)
                    borderRadius(12f)
                }
                event { click { ctx.toggleMA() } }
                Text {
                    attr {
                        text(if (ctx.klineShowMA) "MA 开" else "MA 关")
                        fontSize(11f)
                        color(if (ctx.klineShowMA) 0xFF1976D2 else 0xFF999999)
                    }
                }
            }
        }

        View { attr { height(8f) } }

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
    vfor({ ObservableList(mutableListOf(Triple(ctx.minuteData, ctx.minuteLoading, ctx.minuteError))) }) { (data, loading, error) ->
    minuteCardContent(ctx, data, loading, error)
    }
}

internal fun ViewContainer<*, *>.minuteCardContent(ctx: StockDetailPage, data: List<MinutePoint>?, loading: Boolean, error: String) {

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(8f) }
            Text {
                attr {
                    text(if (data == null) "分时" else "分时（${data.size}分钟）")
                    fontSize(15f); fontWeightBold(); color(0xFF333333); flex(1f)
                }
            }
            Text {
                attr {
                    text(ctx.minuteInfoText.ifEmpty { "点击图表查看" })
                    fontSize(10f); color(0xFF999999); flex(1f); textAlignRight()
                }
            }
        }

        detailAction("重试分时") { ctx.loadMinuteQuote() }
        vif({ loading }) {
            View {
                attr { padding(20f); alignItems(FlexAlign.CENTER) }
                Text { attr { text("分时数据加载中..."); fontSize(12f); color(0xFF999999) } }
            }
        }
        velseif({ error.isNotEmpty() || data.isNullOrEmpty() }) {
            View {
                attr { padding(16f); backgroundColor(0xFFF5F5F5); borderRadius(8f); alignItems(FlexAlign.CENTER) }
                Text { attr { text(if (error.isNotEmpty()) "分时加载失败：$error" else "该股暂无分时数据"); fontSize(13f); color(0xFF666666); fontWeightBold() } }
                Text {
                    attr {
                        text("数据源暂未提供分时，可重试或查看日K行情")
                        fontSize(11f); color(0xFF999999); marginTop(6f); textAlignCenter(); lineHeight(16f)
                    }
                }
                View {
                    attr { marginTop(10f); padding(6f, 12f, 6f, 12f); backgroundColor(0xFFE3F2FD); borderRadius(10f) }
                    event { click { ctx.scrollToChart() } }
                    Text { attr { text("查看K线联动"); fontSize(11f); color(0xFF1976D2) } }
                }
            }
        }
        velse {
            // 控制条
            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
                View {
                    attr {
                        padding(4f, 10f, 4f, 10f)
                        backgroundColor(if (ctx.minuteShowAvg) 0xFFE3F2FD else 0xFFF5F5F5)
                        borderRadius(10f)
                        marginRight(6f)
                    }
                    event { click { ctx.toggleMinuteAvg() } }
                    Text {
                        attr {
                            text(if (ctx.minuteShowAvg) "均线开" else "均线关")
                            fontSize(11f)
                            color(if (ctx.minuteShowAvg) 0xFF1976D2 else 0xFF999999)
                            fontWeightBold()
                        }
                    }
                }
                View {
                    attr {
                        padding(4f, 10f, 4f, 10f)
                        backgroundColor(if (ctx.minuteShowVolume) 0xFFF5F5F5 else 0xFFE8F5E9)
                        borderRadius(10f)
                        marginRight(6f)
                    }
                    event { click { ctx.toggleMinuteVolume() } }
                    Text {
                        attr {
                            text(if (ctx.minuteShowVolume) "成交量" else "无量")
                            fontSize(11f)
                            color(0xFF666666)
                        }
                    }
                }
                View { attr { flex(1f) } }
                vif({ ctx.selectedMinuteIndex >= 0 }) {
                    View {
                        attr { padding(4f, 8f, 4f, 8f); backgroundColor(0xFFF0F2F5); borderRadius(8f) }
                        event { click { ctx.clearMinuteSelection() } }
                        Text { attr { text("清除选中"); fontSize(10f); color(0xFF666666) } }
                    }
                }
            }

            vfor({ ObservableList(mutableListOf(listOf(ctx.aiAnalysis, ctx.highlightedPrice, ctx.selectedMinuteIndex, ctx.minuteShowAvg, ctx.minuteShowVolume))) }) { _ ->
                minuteChartCanvas(ctx, data!!)
            }
            minuteSummary(ctx, data!!)

            // AI价位在分时上的图例
            vfor({ ObservableList(listOfNotNull(ctx.aiAnalysis).toMutableList()) }) { analysis ->
                View {
                    attr { flexDirectionRow(); flexWrapWrap(); marginTop(8f) }
                    val levels = parseAIPriceLevels(analysis)
                    levels.take(3).forEach { lvl ->
                        View {
                            attr {
                                flexDirectionRow()
                                alignItems(FlexAlign.CENTER)
                                marginRight(6f)
                                marginBottom(4f)
                                padding(2f, 6f, 2f, 6f)
                                backgroundColor(0xFFF7F9FC)
                                borderRadius(8f)
                            }
                            event { click { ctx.highlightAIPrice(lvl.price, lvl.label) } }
                            View {
                                attr {
                                    width(6f)
                                    height(6f)
                                    borderRadius(3f)
                                    backgroundColor(lvl.color)
                                    marginRight(4f)
                                }
                            }
                            Text { attr { text("${lvl.label} ${fmt2(lvl.price)}"); fontSize(9f); color(lvl.color) } }
                        }
                    }
                }
            }

            Text {
                attr {
                    text("点击/长按分时图查看价位，自动联动标注到K线，虚线为AI关键价位")
                    fontSize(10f)
                    color(0xFFBBBBBB)
                    marginTop(6f)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.minuteChartCanvas(ctx: StockDetailPage, data: List<MinutePoint>) {
    val aiLevels = parseAIPriceLevels(ctx.aiAnalysis)

    Canvas({
        attr {
            height(if (ctx.minuteShowVolume) 260f else 200f)
            marginTop(4f)
            backgroundColor(0xFFFFFFFF)
        }
        event {
            longPress { params -> ctx.selectMinuteAtX(params.x) }
            click { params -> ctx.selectMinuteAtX(params.x) }
        }
    }) { context, width, height ->
        val n = data.size
        if (n < 2 || width <= 0f || height <= 0f) return@Canvas
        if (ctx.minuteCanvasWidth != width) ctx.minuteCanvasWidth = width

        val tooltipH = 28f
        val padT = 24f
        val volTop = if (ctx.minuteShowVolume) height - 54f else height - 18f
        val volH = if (ctx.minuteShowVolume) 36f else 0f
        val dateY = height - 8f

        val prices = data.map { it.price }
        val avgs = data.mapNotNull { it.avgPrice }
        val preClose = ctx.stockDetail?.realtime?.preClose ?: data.first().price
        var minP = (prices + avgs + aiLevels.map { it.price } + listOfNotNull(ctx.highlightedPrice.takeIf { it > 0 })).minOrNull() ?: 0.0
        var maxP = (prices + avgs + aiLevels.map { it.price } + listOfNotNull(ctx.highlightedPrice.takeIf { it > 0 })).maxOrNull() ?: 1.0
        if (maxP <= minP) maxP = minP + 1.0
        // 以昨收为中心对称，涨跌视觉对称（分时图惯例）；仍包含所有价位点
        if (preClose > 0.0) {
            val half = maxOf(maxP - preClose, preClose - minP).coerceAtLeast(1e-6)
            minP = preClose - half
            maxP = preClose + half
        }
        val pad = (maxP - minP) * 0.12
        minP -= pad
        maxP += pad

        val chartH = volTop - padT - 4f
        fun py(p: Double): Float = padT + chartH * ((maxP - p) / (maxP - minP)).toFloat()
        fun px(i: Int): Float = if (n <= 1) 0f else (width * i / (n - 1).toFloat())

        // 网格
        context.strokeStyle(Color(0xFFF0F2F5))
        context.lineWidth(1f)
        for (i in 0..3) {
            val gy = padT + chartH * i / 3f
            context.beginPath()
            context.moveTo(0f, gy)
            context.lineTo(width, gy)
            context.stroke()
        }
        // 垂直分割（上午/下午）
        if (n > 120) {
            val midX = width * 0.5f
            context.strokeStyle(Color(0xFFEEEEEE))
            context.beginPath()
            context.moveTo(midX, padT)
            context.lineTo(midX, volTop)
            context.stroke()
        }

        // 昨收基准 + 涨跌区域填充（伪渐变：逐段低透明度四边形，段中价≥昨收红 / 否则绿）
        val baseY = py(preClose)
        if (preClose > 0.0) {
            for (i in 0 until n - 1) {
                val x0 = px(i)
                val x1 = px(i + 1)
                val y0 = py(data[i].price)
                val y1 = py(data[i + 1].price)
                val mid = (data[i].price + data[i + 1].price) / 2.0
                context.fillStyle(Color(if (mid >= preClose) 0x18E53935L else 0x1843A047L))
                context.beginPath()
                context.moveTo(x0, y0)
                context.lineTo(x1, y1)
                context.lineTo(x1, baseY)
                context.lineTo(x0, baseY)
                context.closePath()
                context.fill()
            }
            // 昨收虚线（灰）+ 右侧标签
            context.strokeStyle(Color(0xFF999999))
            context.lineWidth(1f)
            var bx = 0f
            while (bx < width) {
                context.beginPath()
                context.moveTo(bx, baseY)
                context.lineTo((bx + 5f).coerceAtMost(width), baseY)
                context.stroke()
                bx += 9f
            }
            context.fillStyle(Color(0xFF999999))
            context.font(8f)
            context.textAlign(TextAlign.RIGHT)
            context.fillText("昨收 ${fmt2(preClose)}", width - 2f, baseY - 2f)
        }

        // AI价位虚线
        aiLevels.forEach { lvl ->
            if (lvl.price in minP..maxP) {
                val y = py(lvl.price)
                context.strokeStyle(Color(lvl.color))
                context.lineWidth(0.8f)
                var x = 0f
                while (x < width) {
                    context.beginPath()
                    context.moveTo(x, y)
                    context.lineTo((x + 5f).coerceAtMost(width), y)
                    context.stroke()
                    x += 9f
                }
                context.fillStyle(Color(lvl.color))
                context.font(8f)
                context.textAlign(TextAlign.RIGHT)
                context.fillText("${lvl.label}", width - 2f, y - 2f)
            }
        }

        // 高亮价位
        if (ctx.highlightedPrice > 0 && ctx.highlightedPrice in minP..maxP) {
            val y = py(ctx.highlightedPrice)
            context.strokeStyle(Color(0xFFFF9800))
            context.lineWidth(1.5f)
            context.beginPath()
            context.moveTo(0f, y)
            context.lineTo(width, y)
            context.stroke()
        }

        // 分时价格线
        context.strokeStyle(Color(0xFF1976D2))
        context.lineWidth(1.5f)
        context.beginPath()
        data.forEachIndexed { i, p ->
            val x = px(i)
            val y = py(p.price)
            if (i == 0) context.moveTo(x, y) else context.lineTo(x, y)
        }
        context.stroke()

        // 均线
        if (ctx.minuteShowAvg && avgs.isNotEmpty()) {
            context.strokeStyle(Color(0xFFFF9800))
            context.lineWidth(1f)
            context.beginPath()
            var started = false
            data.forEachIndexed { i, p ->
                val avg = p.avgPrice ?: return@forEachIndexed
                val x = px(i)
                val y = py(avg)
                if (!started) {
                    context.moveTo(x, y)
                    started = true
                } else {
                    context.lineTo(x, y)
                }
            }
            context.stroke()
        }

        // 成交量
        if (ctx.minuteShowVolume && volH > 0) {
            val vols = data.map { it.volume ?: 0.0 }
            val maxVol = vols.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
            data.forEachIndexed { i, p ->
                val x = px(i)
                val vol = (p.volume ?: 0.0).toFloat()
                if (vol <= 0f) return@forEachIndexed
                val vh = (volH * (vol / maxVol)).coerceAtLeast(1f)
                val up = p.price >= preClose
                context.fillStyle(Color(if (up) 0xFFE53935 else 0xFF43A047))
                context.beginPath()
                context.moveTo(x - 1f, volTop + volH)
                context.lineTo(x + 1f, volTop + volH)
                context.lineTo(x + 1f, volTop + volH - vh)
                context.lineTo(x - 1f, volTop + volH - vh)
                context.closePath()
                context.fill()
            }
        }

        // 价格标签
        context.fillStyle(Color(0xFF999999))
        context.font(9f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(maxP), 2f, padT + 8f)
        context.fillText(fmt2(minP), 2f, volTop - 2f)
        context.textAlign(TextAlign.RIGHT)
        context.fillText(fmt2(data.last().price), width - 2f, padT + 8f)

        // 时间标签
        context.font(9f)
        context.fillStyle(Color(0xFF999999))
        context.textAlign(TextAlign.LEFT)
        context.fillText(data.first().time, 2f, dateY)
        context.textAlign(TextAlign.CENTER)
        if (n > 60) context.fillText(data[n / 2].time, width * 0.5f, dateY)
        context.textAlign(TextAlign.RIGHT)
        context.fillText(data.last().time, width - 2f, dateY)

        // 选中十字
        val sel = ctx.selectedMinuteIndex
        if (sel >= 0 && sel < n) {
            val p = data[sel]
            val cx = px(sel)
            val cy = py(p.price)
            context.strokeStyle(Color(0xFF333333))
            context.lineWidth(0.8f)
            var vy = padT
            while (vy < volTop) {
                context.beginPath()
                context.moveTo(cx, vy)
                context.lineTo(cx, (vy + 3f).coerceAtMost(volTop))
                context.stroke()
                vy += 7f
            }
            var hx = 0f
            while (hx < width) {
                context.beginPath()
                context.moveTo(hx, cy)
                context.lineTo((hx + 3f).coerceAtMost(width), cy)
                context.stroke()
                hx += 7f
            }
            // 点
            context.fillStyle(Color(0xFF1976D2))
            context.beginPath()
            context.moveTo(cx - 3f, cy)
            context.lineTo(cx + 3f, cy)
            context.lineTo(cx, cy - 3f)
            context.closePath()
            context.fill()

            // tooltip
            context.fillStyle(Color(0xE622263F))
            context.beginPath()
            context.moveTo(0f, 0f)
            context.lineTo(width, 0f)
            context.lineTo(width, tooltipH)
            context.lineTo(0f, tooltipH)
            context.closePath()
            context.fill()
            context.fillStyle(Color(0xFFFFFFFF))
            context.font(9f)
            context.textAlign(TextAlign.LEFT)
            context.fillText(
                "${p.time} 价${fmt2(p.price)}${p.avgPrice?.let { " 均${fmt2(it)}" } ?: ""} ${p.volume?.let { "量${it.toInt()}" } ?: ""}",
                4f, 10f
            )
            val pct = if (preClose != 0.0) (p.price - preClose) / preClose * 100.0 else 0.0
            context.fillStyle(Color(if (pct >= 0) 0xFFFF8A80 else 0xFFA5D6A7))
            context.fillText("涨跌 ${fmtSignedPct(pct)}", 4f, 20f)
        }
    }
}

internal fun ViewContainer<*, *>.minuteSummary(ctx: StockDetailPage, data: List<MinutePoint>) {
    if (data.isEmpty()) return
    val latest = data.last()
    val first = data.first()
    val prices = data.map { it.price }
    val hi = prices.maxOrNull() ?: latest.price
    val lo = prices.minOrNull() ?: latest.price
    val preClose = ctx.stockDetail?.realtime?.preClose ?: first.price
    val change = latest.price - preClose
    val pct = if (preClose != 0.0) change / preClose * 100.0 else 0.0

    View {
        attr { flexDirectionColumn(); marginTop(8f) }
        View { attr { height(1f); backgroundColor(0xFFEEEEEE); marginBottom(6f) } }
        View {
            attr { flexDirectionRow(); flexWrapWrap() }
            Text { attr { text("今开 ${fmt2(first.price)}"); fontSize(11f); color(0xFF666666) } }
            Text { attr { text("最新 ${fmt2(latest.price)}"); fontSize(11f); fontWeightBold(); color(0xFF333333); marginLeft(10f) } }
            Text {
                attr {
                    text("${fmtSigned2(change)} ${fmtSignedPct(pct)}")
                    fontSize(11f)
                    color(if (pct >= 0) 0xFFE53935 else 0xFF43A047)
                    marginLeft(8f)
                }
            }
        }
        View {
            attr { flexDirectionRow(); marginTop(4f) }
            Text { attr { text("最高 ${fmt2(hi)}  最低 ${fmt2(lo)}"); fontSize(11f); color(0xFF666666) } }
            Text { attr { text("振幅 ${fmt2((hi - lo) / preClose * 100.0)}%"); fontSize(11f); color(0xFF999999); marginLeft(10f) } }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookCard(ctx: StockDetailPage) {
    View {
        attr { margin(4f, 12f, 4f, 12f); padding(12f); backgroundColor(0xFFFFFFFF); borderRadius(10f) }
        Text { attr { text("五档盘口"); fontSize(15f); fontWeightBold() } }
        vif({ ctx.orderBookLoading }) { Text { attr { text("盘口数据加载中…"); fontSize(12f) } } }
        velseif({ ctx.orderBookError.isNotEmpty() || ctx.orderBook == null }) {
            Text { attr { text(ctx.orderBookError.ifEmpty { "暂无盘口数据" }); fontSize(12f) } }
            detailAction("重试盘口") { ctx.loadBookQuote() }
            detailAction("查看K线") { ctx.scrollToChart() }
        }
        velse { vfor({ ObservableList(listOfNotNull(ctx.orderBook).toMutableList()) }) { book -> orderBookCardContent(ctx, book) } }
    }
}

internal fun ViewContainer<*, *>.orderBookCardContent(ctx: StockDetailPage, book: OrderBookData) {
    // 派生量：各档量能比例条 / 委差 / 大单判定
    val levelVols = (book.bids + book.asks).mapNotNull { it.second }.filter { it.isFinite() && it >= 0.0 }
    val maxLevelVol = levelVols.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val meanLevelVol = if (levelVols.isEmpty()) 0.0 else levelVols.average()
    val bidVolTotal = book.bids.sumOf { it.second ?: 0.0 }
    val askVolTotal = book.asks.sumOf { it.second ?: 0.0 }
    val weicha = bidVolTotal - askVolTotal  // 委差(手)：买总量 - 卖总量

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(8f) }
            Text {
                attr {
                    text("五档盘口${book.updateTime?.let { "（$it）" } ?: ""}")
                    fontSize(15f); fontWeightBold(); color(0xFF333333); flex(1f)
                }
            }
            View {
                attr {
                    padding(4f, 10f, 4f, 10f)
                    backgroundColor(if (ctx.orderBookMode == "depth") 0xFFE3F2FD else 0xFFF5F5F5)
                    borderRadius(10f)
                    marginRight(6f)
                }
                event { click { ctx.toggleOrderBookMode() } }
                Text {
                    attr {
                        text(if (ctx.orderBookMode == "depth") "深度图" else "列表")
                        fontSize(11f)
                        color(if (ctx.orderBookMode == "depth") 0xFF1976D2 else 0xFF666666)
                        fontWeightBold()
                    }
                }
            }
            Text {
                attr {
                    text("点击价位联动K线")
                    fontSize(10f)
                    color(0xFF999999)
                }
            }
        }

        vif({ ctx.orderBookMode == "depth" }) {
            orderBookDepthCanvas(ctx, book)
        }

        // 列表
        book.asks.reversed().forEachIndexed { i, (price, vol) ->
            val r = (vol ?: 0.0) / maxLevelVol
            val big = vol != null && meanLevelVol > 0.0 && vol >= meanLevelVol * 1.8
            orderBookRow(ctx, "卖${5 - i}", price, vol, 0xFF43A047, isAsk = true, ratio = r, isBig = big)
        }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); margin(6f, 0f, 6f, 0f) }
            View { attr { height(1f); backgroundColor(0xFFEEEEEE); flex(1f) } }
            Text {
                attr {
                    text("现价 ${ctx.stockDetail?.realtime?.price?.let { fmt2(it) } ?: "-"}")
                    fontSize(11f)
                    color(0xFF1976D2)
                    fontWeightBold()
                    marginLeft(8f)
                    marginRight(8f)
                }
            }
            View { attr { height(1f); backgroundColor(0xFFEEEEEE); flex(1f) } }
        }
        book.bids.forEachIndexed { i, (price, vol) ->
            val r = (vol ?: 0.0) / maxLevelVol
            val big = vol != null && meanLevelVol > 0.0 && vol >= meanLevelVol * 1.8
            orderBookRow(ctx, "买${i + 1}", price, vol, 0xFFE53935, isAsk = false, ratio = r, isBig = big)
        }

        View {
            attr { flexDirectionRow(); marginTop(10f); flexWrapWrap() }
            book.commissionRatio?.let { ratio ->
                View {
                    attr {
                        padding(4f, 8f, 4f, 8f)
                        backgroundColor(0xFFF5F5F5)
                        borderRadius(8f)
                        marginRight(6f)
                    }
                    Text { attr { text("委比 ${fmt2(ratio)}%"); fontSize(11f); color(0xFF666666) } }
                }
            }
            // 委差（手）：买总量 - 卖总量，>0 偏多(红)、<0 偏空(绿)
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(8f)
                    marginRight(6f)
                }
                Text {
                    attr {
                        text("委差 ${if (weicha >= 0) "+" else ""}${weicha.toInt()}手")
                        fontSize(11f)
                        color(if (weicha >= 0) 0xFFE53935 else 0xFF43A047)
                    }
                }
            }
            vif({ ctx.orderBookHighlightPrice > 0 }) {
                View {
                    attr {
                        flexDirectionRow()
                        alignItems(FlexAlign.CENTER)
                        padding(4f, 8f, 4f, 8f)
                        backgroundColor(0xFFFFF3E8)
                        borderRadius(8f)
                    }
                    Text {
                        attr {
                            text("已标注 ¥${fmt2(ctx.orderBookHighlightPrice)}")
                            fontSize(11f)
                            color(0xFFA56100)
                        }
                    }
                    View {
                        attr { marginLeft(6f); padding(2f, 6f, 2f, 6f); backgroundColor(0xFFFFFFFF); borderRadius(6f) }
                        event { click { ctx.clearHighlight() } }
                        Text { attr { text("✕"); fontSize(10f); color(0xFF999999) } }
                    }
                }
            }
        }

        Text {
            attr {
                text("盘口价位可一键标注到K线，或设提醒，联动分时与日K")
                fontSize(10f)
                color(0xFFBBBBBB)
                marginTop(8f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookRow(ctx: StockDetailPage, label: String, price: Double?, vol: Double?, color: Long, isAsk: Boolean, ratio: Double, isBig: Boolean) {
    val isHighlighted = price != null && ctx.orderBookHighlightPrice == price
    val barFlex = (ratio.coerceIn(0.0, 1.0) * 1000).toInt().coerceIn(0, 1000)
    View {
        attr {
            flexDirectionRow()
            marginTop(3f)
            alignItems(FlexAlign.CENTER)
            padding(4f, 6f, 4f, 6f)
            backgroundColor(if (isHighlighted) 0xFFFFF3E8 else 0xFFFFFFFF)
            borderRadius(6f)
        }
        // 量能比例背景条（绝对铺底、无事件，不拦截点击；靠右填充，买红/卖绿低透明度）
        View {
            attr { absolutePositionAllZero(); flexDirectionRow(); borderRadius(6f) }
            View { attr { flex((1000 - barFlex).toFloat()) } }
            View { attr { flex(barFlex.toFloat()); backgroundColor(if (isAsk) 0x1543A047L else 0x15E53935L) } }
        }
        Text { attr { text(label); fontSize(12f); color(0xFF666666); width(36f) } }
        Text { attr { text(fmtOpt(price)); fontSize(13f); fontWeightBold(); color(color); flex(1f) } }
        Text { attr { text(fmtOpt(vol)); fontSize(11f); color(0xFF666666); width(60f); textAlignRight() } }

        vif({ isBig }) {
            View {
                attr {
                    marginLeft(6f)
                    padding(2f, 5f, 2f, 5f)
                    backgroundColor(if (isAsk) 0xFFEAF7EF else 0xFFFFF0F0)
                    borderRadius(6f)
                }
                Text { attr { text("大单"); fontSize(9f); color(if (isAsk) 0xFF2E7D32 else 0xFFC62828); fontWeightBold() } }
            }
        }
        View {
            attr {
                marginLeft(6f)
                padding(3f, 8f, 3f, 8f)
                backgroundColor(if (isHighlighted) 0xFFFFE0B2 else 0xFFF0F2F5)
                borderRadius(8f)
            }
            event {
                click {
                    if (price != null) ctx.highlightOrderBookPrice(price, label)
                }
            }
            Text { attr { text(if (isHighlighted) "已标" else "标注"); fontSize(10f); color(if (isHighlighted) 0xFFA56100 else 0xFF666666); fontWeightBold() } }
        }
        View {
            attr {
                marginLeft(4f)
                padding(3f, 8f, 3f, 8f)
                backgroundColor(Color(color))
                borderRadius(8f)
            }
            event {
                click {
                    if (price != null) ctx.prepareAlertFromOrderBook(price)
                }
            }
            Text { attr { text("提醒"); fontSize(10f); color(0xFFFFFFFF); fontWeightBold() } }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookDepthCanvas(ctx: StockDetailPage, book: OrderBookData) {
    Canvas({
        attr {
            height(120f)
            marginBottom(8f)
            backgroundColor(0xFFFAFAFA)
            borderRadius(8f)
        }
    }) { context, width, height ->
        if (width <= 0f || height <= 0f) return@Canvas
        val bids = book.bids.filter { (price, _) -> price != null && price.isFinite() && price > 0 }
        val asks = book.asks.filter { (price, _) -> price != null && price.isFinite() && price > 0 }
        if (bids.isEmpty() && asks.isEmpty()) return@Canvas

        val allPrices = (bids.map { it.first!! } + asks.map { it.first!! })
        var minP = allPrices.minOrNull() ?: 0.0
        var maxP = allPrices.maxOrNull() ?: 1.0
        if (maxP <= minP) maxP = minP + 1.0
        val pad = (maxP - minP) * 0.1
        minP -= pad
        maxP += pad

        val allVols = (bids.map { it.second ?: 0.0 } + asks.map { it.second ?: 0.0 })
        val maxVol = allVols.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f

        fun py(vol: Double): Float = height - 16f - (vol.toFloat() / maxVol) * (height - 32f)
        fun px(price: Double): Float = ((price - minP) / (maxP - minP)).toFloat() * (width - 16f) + 8f

        // 买盘
        context.fillStyle(Color(0x33E53935))
        context.strokeStyle(Color(0xFFE53935))
        context.lineWidth(1f)
        if (bids.isNotEmpty()) {
            val sortedBids = bids.sortedBy { it.first }
            context.beginPath()
            context.moveTo(px(sortedBids.first().first!!), height - 16f)
            sortedBids.forEach { (price, vol) ->
                context.lineTo(px(price!!), py(vol ?: 0.0))
            }
            context.lineTo(px(sortedBids.last().first!!), height - 16f)
            context.closePath()
            context.fill()
            context.beginPath()
            sortedBids.forEachIndexed { i, (price, vol) ->
                val x = px(price!!)
                val y = py(vol ?: 0.0)
                if (i == 0) context.moveTo(x, y) else context.lineTo(x, y)
            }
            context.stroke()
        }

        // 卖盘
        context.fillStyle(Color(0x3343A047))
        context.strokeStyle(Color(0xFF43A047))
        if (asks.isNotEmpty()) {
            val sortedAsks = asks.sortedBy { it.first }
            context.beginPath()
            context.moveTo(px(sortedAsks.first().first!!), height - 16f)
            sortedAsks.forEach { (price, vol) ->
                context.lineTo(px(price!!), py(vol ?: 0.0))
            }
            context.lineTo(px(sortedAsks.last().first!!), height - 16f)
            context.closePath()
            context.fill()
            context.beginPath()
            sortedAsks.forEachIndexed { i, (price, vol) ->
                val x = px(price!!)
                val y = py(vol ?: 0.0)
                if (i == 0) context.moveTo(x, y) else context.lineTo(x, y)
            }
            context.stroke()
        }

        // 现价线
        ctx.stockDetail?.realtime?.price?.let { curPrice ->
            if (curPrice in minP..maxP) {
                val x = px(curPrice)
                context.strokeStyle(Color(0xFF1976D2))
                context.lineWidth(1f)
                context.beginPath()
                context.moveTo(x, 0f)
                context.lineTo(x, height - 16f)
                context.stroke()
                context.fillStyle(Color(0xFF1976D2))
                context.font(8f)
                context.textAlign(TextAlign.CENTER)
                context.fillText("现价", x, 10f)
            }
        }

        // 标签
        context.fillStyle(Color(0xFF999999))
        context.font(8f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(minP), 2f, height - 2f)
        context.textAlign(TextAlign.RIGHT)
        context.fillText(fmt2(maxP), width - 2f, height - 2f)
    }
}

private fun fmtOpt(v: Double?): String = if (v == null) "-" else fmt2(v)

// --- K线区域：重构为高交互版本 ---

internal fun ViewContainer<*, *>.klineChartArea(ctx: StockDetailPage) {
    val originalKline = ctx.stockDetail?.kline

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 10f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        // 标题 + 周期切换
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(8f) }
            Text {
                attr {
                    text("K线走势")
                    fontSize(15f)
                    fontWeightBold()
                    color(0xFF333333)
                    flex(1f)
                }
            }
            Text {
                attr {
                    text(ctx.klineInfoText.ifEmpty { "${ctx.getAggregatedKline().size}根 · ${ctx.klineVisibleCount}显示" })
                    fontSize(10f)
                    color(0xFF999999)
                    flex(1f)
                    textAlignRight()
                }
            }
        }

        // 周期切换
        View {
            attr { flexDirectionRow(); marginBottom(8f) }
            klinePeriodChip(ctx, "D", "日K")
            klinePeriodChip(ctx, "W", "周K")
            klinePeriodChip(ctx, "M", "月K")
            View { attr { flex(1f) } }
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(if (ctx.klineShowMA) 0xFFE3F2FD else 0xFFF5F5F5)
                    borderRadius(10f)
                    marginRight(6f)
                }
                event { click { ctx.toggleMA() } }
                Text {
                    attr {
                        text("MA")
                        fontSize(11f)
                        color(if (ctx.klineShowMA) 0xFF1976D2 else 0xFF999999)
                        fontWeightBold()
                    }
                }
            }
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(10f)
                }
                event { click { ctx.resetView() } }
                Text { attr { text("重置"); fontSize(11f); color(0xFF666666) } }
            }
        }

        vif({ ctx.isLoading }) {
            klineLoadingView()
        }
        velseif({ originalKline != null && originalKline.isNotEmpty() }) {
            // 缩放平移控制
            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
                klineControlBtn(ctx, "◀◀", { ctx.panLeft() })
                klineControlBtn(ctx, "－", { ctx.zoomIn() })
                Text {
                    attr {
                        text("${ctx.klineVisibleCount}根")
                        fontSize(11f)
                        color(0xFF666666)
                        marginLeft(6f)
                        marginRight(6f)
                        width(40f)
                        textAlignCenter()
                    }
                }
                klineControlBtn(ctx, "＋", { ctx.zoomOut() })
                klineControlBtn(ctx, "▶▶", { ctx.panRight() })
                View { attr { flex(1f) } }
                vif({ ctx.highlightedPrice > 0 }) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItems(FlexAlign.CENTER)
                            backgroundColor(0xFFFFF3E8)
                            borderRadius(8f)
                            padding(3f, 8f, 3f, 8f)
                        }
                        Text {
                            attr {
                                text("${ctx.highlightedPriceLabel} ¥${fmt2(ctx.highlightedPrice)}")
                                fontSize(11f)
                                color(0xFFA56100)
                            }
                        }
                        View {
                            attr { marginLeft(6f); padding(2f, 6f, 2f, 6f); backgroundColor(0xFFFFFFFF); borderRadius(6f) }
                            event { click { ctx.clearHighlight() } }
                            Text { attr { text("✕"); fontSize(10f); color(0xFF999999) } }
                        }
                    }
                }
            }

            // 副图指标选择器（关 / MACD / KDJ，默认关；开启时主画布向下增高）
            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
                Text { attr { text("副图"); fontSize(11f); color(0xFF999999); marginRight(8f) } }
                subIndicatorChip(ctx, "none", "关")
                subIndicatorChip(ctx, "macd", "MACD")
                subIndicatorChip(ctx, "kdj", "KDJ")
            }

            vfor({ ObservableList(mutableListOf(listOf(ctx.getAggregatedKline(), ctx.klineStartIndex, ctx.klineVisibleCount, ctx.selectedKlineIndex, ctx.aiAnalysis, ctx.highlightedPrice, ctx.highlightedPriceLabel, ctx.klineShowMA, ctx.klineShowVolume, ctx.klinePeriod, ctx.effectiveVerdict, ctx.verdictExpanded, ctx.isAnalyzing, ctx.crosshairX, ctx.crosshairY, ctx.rangeStats, ctx.isRangeSelecting, ctx.klineSubIndicator))) }) { _ ->
            View {
                attr { flexDirectionColumn() }
                event { layoutFrameDidChange { ctx.klineSectionY = it.y } }
                aiVerdictBar(ctx)
                klineChartCanvas(ctx, ctx.getAggregatedKline())
                klineSummary(ctx, ctx.getVisibleKline())
            }
            }
            chartEvidencePanel({ ctx.getAggregatedKline() }, { ctx.selectedKlineIndex }, { ctx.aiAnalysis }, { ctx.focusCandle(it) }, { ctx.askAboutChart() })

            // AI价位图例
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
                                        "support" -> 0xFFEAF7EF
                                        "resistance" -> 0xFFFFF0F0
                                        "target" -> 0xFFE8F2FF
                                        else -> 0xFFFFF3E8
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
                    color(0xFFBBBBBB)
                    marginTop(6f)
                }
            }
        }
        velse {
            klineErrorView(ctx)
        }
    }
}

internal fun ViewContainer<*, *>.klinePeriodChip(ctx: StockDetailPage, period: String, label: String) {
    View {
        attr {
            padding(5f, 12f, 5f, 12f)
            backgroundColor(if (ctx.klinePeriod == period) 0xFF1976D2 else 0xFFF5F5F5)
            borderRadius(14f)
            marginRight(6f)
        }
        event { click { ctx.switchKlinePeriod(period) } }
        Text {
            attr {
                text(label)
                fontSize(12f)
                fontWeightBold()
                color(if (ctx.klinePeriod == period) 0xFFFFFFFF else 0xFF666666)
            }
        }
    }
}

internal fun ViewContainer<*, *>.subIndicatorChip(ctx: StockDetailPage, key: String, label: String) {
    val active = ctx.klineSubIndicator == key
    View {
        attr {
            padding(3f, 10f, 3f, 10f)
            backgroundColor(if (active) 0xFF5B7FFF else 0xFFF5F5F5)
            borderRadius(12f)
            marginRight(6f)
        }
        event { click { ctx.klineSubIndicator = key } }
        Text {
            attr {
                text(label)
                fontSize(11f)
                fontWeightBold()
                color(if (active) 0xFFFFFFFF else 0xFF888888)
            }
        }
    }
}

internal fun ViewContainer<*, *>.klineControlBtn(ctx: StockDetailPage, label: String, action: () -> Unit) {
    View {
        attr {
            width(32f)
            height(28f)
            backgroundColor(0xFFF5F5F5)
            borderRadius(8f)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            marginRight(4f)
        }
        event { click { action() } }
        Text {
            attr {
                text(label)
                fontSize(12f)
                color(0xFF666666)
                textAlignCenter()
            }
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

internal fun ViewContainer<*, *>.klineChartCanvas(ctx: StockDetailPage, aggregated: List<KLineDataItem>) {
    val visible = ctx.getVisibleKline()
    val aiLevels = parseAIPriceLevels(ctx.aiAnalysis)

    Canvas({
        attr {
            height((if (ctx.klineShowVolume) 380f else 300f) + (if (ctx.klineSubIndicator != "none") 86f else 0f))
            marginTop(2f)
            backgroundColor(0xFFFFFFFF)
        }
        event {
            longPress { params ->
                ctx.beginRangeSelect(params.x)
            }
            click { params ->
                ctx.tapCrosshair(params.x)
            }
        }
    }) { context, width, height ->
        val nTotal = aggregated.size
        val nVisible = visible.size
        if (nTotal == 0 || nVisible == 0 || width <= 0f || height <= 0f) return@Canvas

        if (ctx.klineCanvasWidth != width) {
            ctx.klineCanvasWidth = width
        }

        val tooltipH = 36f
        val padT = 38f
        // 副图（MACD/KDJ）：底部预留一块高度。height 已含 +86，故 volTop 回落到 base-70，
        // 主图与量图区域逐像素不变；副图占 [subTop, subBottom]。
        val subOn = ctx.klineSubIndicator != "none"
        val subReserve = if (subOn) 86f else 0f       // 76 高 + 10 间隙
        val volTop = (if (ctx.klineShowVolume) height - 70f else height - 20f) - subReserve
        val volH = if (ctx.klineShowVolume) 44f else 0f
        val dateY = height - 10f
        val subTop = volTop + volH + 10f
        val subBottom = subTop + 76f

        // 计算可见区间价格范围，包含AI价位
        val priceList = visible.flatMap { listOf(it.high, it.low, it.open, it.close) }.toMutableList()
        aiLevels.forEach { if (it.price > 0) priceList.add(it.price) }
        if (ctx.highlightedPrice > 0) priceList.add(ctx.highlightedPrice)
        var minP = priceList.minOrNull() ?: 0.0
        var maxP = priceList.maxOrNull() ?: 1.0
        if (maxP <= minP) maxP = minP + 1.0
        // 留出10%边距
        val pad = (maxP - minP) * 0.08
        minP -= pad
        maxP += pad

        val chartH = volTop - padT - 6f
        fun py(p: Double): Float = padT + chartH * ((maxP - p) / (maxP - minP)).toFloat()

        val step = width / nVisible.coerceAtLeast(1)
        val cw = (step * 0.55f).coerceAtLeast(2f).coerceAtMost(14f)

        // 背景网格
        context.strokeStyle(Color(0xFFF0F2F5))
        context.lineWidth(1f)
        for (i in 0..4) {
            val gy = padT + chartH * i / 4f
            context.beginPath()
            context.moveTo(0f, gy)
            context.lineTo(width, gy)
            context.stroke()
        }
        // 垂直网格
        context.strokeStyle(Color(0xFFFAFAFA))
        for (i in 0..nVisible step (nVisible / 5).coerceAtLeast(1)) {
            val cx = step * i + step / 2f
            context.beginPath()
            context.moveTo(cx, padT)
            context.lineTo(cx, volTop)
            context.stroke()
        }

        // AI价位虚线
        aiLevels.forEach { lvl ->
            if (lvl.price in minP..maxP) {
                val y = py(lvl.price)
                // 虚线效果：手动分段
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
                // 标签
                context.fillStyle(Color(lvl.color))
                context.font(9f)
                context.textAlign(TextAlign.RIGHT)
                context.fillText("${lvl.label} ${fmt2(lvl.price)}", width - 2f, y - 3f)
            }
        }

        // 高亮价位
        if (ctx.highlightedPrice > 0 && ctx.highlightedPrice in minP..maxP) {
            val y = py(ctx.highlightedPrice)
            context.strokeStyle(Color(0xFFFF9800))
            context.lineWidth(2f)
            context.beginPath()
            context.moveTo(0f, y)
            context.lineTo(width, y)
            context.stroke()
            context.fillStyle(Color(0xFFFF9800))
            context.font(10f)
            context.textAlign(TextAlign.LEFT)
            context.fillText("★ ${ctx.highlightedPriceLabel} ${fmt2(ctx.highlightedPrice)}", 4f, y - 4f)
        }

        // 计算MA
        val closes = aggregated.map { it.close }
        fun maAt(index: Int, period: Int): Double? {
            if (index < period - 1) return null
            return closes.subList(index - period + 1, index + 1).average()
        }
        // 为可见区间准备MA点
        val visibleStart = ctx.klineStartIndex
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

        // K线蜡烛
        visible.forEachIndexed { i, k ->
            val cx = step * i + step / 2f
            val up = k.close >= k.open
            val color = if (up) Color(0xFFE53935) else Color(0xFF43A047)
            // 影线
            context.strokeStyle(color)
            context.lineWidth(1f)
            context.beginPath()
            context.moveTo(cx, py(k.high))
            context.lineTo(cx, py(k.low))
            context.stroke()
            // 实体
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

        // MA线
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
            drawMALine(ma5Points, Color(0xFF1976D2))
            drawMALine(ma10Points, Color(0xFFFF9800))
            drawMALine(ma20Points, Color(0xFF7B1FA2))
        }

        // 成交量
        if (ctx.klineShowVolume && volH > 0) {
            val maxVol = visible.maxOf { it.volume }.toFloat().coerceAtLeast(1f)
            context.strokeStyle(Color(0xFFE0E0E0))
            context.lineWidth(1f)
            context.beginPath()
            context.moveTo(0f, volTop - 5f)
            context.lineTo(width, volTop - 5f)
            context.stroke()
            visible.forEachIndexed { i, k ->
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
        }

        // ============ 副图：MACD / KDJ（并入主画布，X 轴与主图逐根对齐）============
        if (subOn) {
            val subH = subBottom - subTop
            // 与量图/主图的分隔线
            context.strokeStyle(Color(0xFFE0E0E0))
            context.lineWidth(1f)
            context.beginPath()
            context.moveTo(0f, subTop - 5f)
            context.lineTo(width, subTop - 5f)
            context.stroke()

            // 在完整 aggregated 上计算（EMA/窗口预热），再按可见区间切片，与 maAt 同理
            val closesAll = aggregated.map { it.close }
            val subActiveIdx = ctx.crosshair.activeIndex ?: -1
            val subActiveLocal = (subActiveIdx - visibleStart).let { if (it in 0 until nVisible) it else nVisible - 1 }

            if (ctx.klineSubIndicator == "macd") {
                val macd = computeMACD(closesAll)
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
                // 零轴
                context.strokeStyle(Color(0xFFCCCCCC))
                context.lineWidth(1f)
                context.beginPath(); context.moveTo(0f, zeroY); context.lineTo(width, zeroY); context.stroke()
                // MACD 柱（≥0 红 / <0 绿）
                for (i in 0 until nVisible) {
                    val cx = step * i + step / 2f
                    val h = histV[i]
                    val y = syToY(h)
                    context.fillStyle(Color(if (h >= 0.0) 0xFFE53935 else 0xFF43A047))
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
                drawSubLine(difV, 0xFF1976D2)  // DIF 蓝
                drawSubLine(deaV, 0xFFFF9800)  // DEA 橙
                context.fillStyle(Color(0xFF888888)); context.font(9f); context.textAlign(TextAlign.LEFT)
                val si = subActiveLocal.coerceIn(0, nVisible - 1)
                context.fillText("MACD(12,26,9)  DIF ${fmt2(difV[si])}  DEA ${fmt2(deaV[si])}  M ${fmt2(histV[si])}", 4f, subTop + 9f)
            } else {
                val highsAll = aggregated.map { it.high }
                val lowsAll = aggregated.map { it.low }
                val kdj = computeKDJ(highsAll, lowsAll, closesAll)
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
                // 20 / 80 参考虚线
                context.strokeStyle(Color(0xFFEEEEEE)); context.lineWidth(1f)
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
                drawSubLine(kV, 0xFF1976D2)  // K 蓝
                drawSubLine(dV, 0xFFFF9800)  // D 橙
                drawSubLine(jV, 0xFF7B1FA2)  // J 紫
                context.fillStyle(Color(0xFF888888)); context.font(9f); context.textAlign(TextAlign.LEFT)
                val si = subActiveLocal.coerceIn(0, nVisible - 1)
                context.fillText("KDJ(9,3,3)  K ${fmt2(kV[si])}  D ${fmt2(dV[si])}  J ${fmt2(jV[si])}", 4f, subTop + 9f)
            }
        }

        // 价格标签
        context.fillStyle(Color(0xFF999999))
        context.font(10f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(maxP), 4f, padT + 9f)
        context.fillText(fmt2(minP), 4f, volTop - 6f)

        // 日期标签
        if (visible.isNotEmpty()) {
            val firstDate = visible.first().tradeDate
            val lastDate = visible.last().tradeDate
            context.font(9f)
            context.textAlign(TextAlign.LEFT)
            context.fillText(firstDate, 2f, dateY)
            context.textAlign(TextAlign.RIGHT)
            context.fillText(lastDate, width - 2f, dateY)
            // 中间日期
            if (nVisible > 10) {
                context.textAlign(TextAlign.CENTER)
                context.fillText(visible[nVisible / 2].tradeDate, width / 2f, dateY)
            }
        }

        // P1: 十字光标 + 区间选择
        val interaction = ctx.crosshair.state
        val showCrosshair = ctx.crosshairX >= 0f && (interaction is InteractionState.Hover || interaction is InteractionState.Locked || interaction is InteractionState.RangeSelect)

        if (showCrosshair) {
            val cx = ctx.crosshairX.coerceIn(0f, width)
            val activeIdx = ctx.crosshair.activeIndex ?: -1
            val localIdx = activeIdx - ctx.klineStartIndex
            val k = visible.getOrNull(localIdx)

            // 竖直线（虚线）— 副图开启时贯穿主图 + 量图 + 副图
            context.strokeStyle(Color(0xFF666666))
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

            // 水平线（虚线）— 跟随 crosshairY 或收盘价
            val hy = if (ctx.crosshairY in padT..volTop) ctx.crosshairY else (k?.let { py(it.close) } ?: (padT + chartH / 2))
            var hx = 0f
            while (hx < width) {
                context.beginPath()
                context.moveTo(hx, hy)
                context.lineTo((hx + 4f).coerceAtMost(width), hy)
                context.stroke()
                hx += 8f
            }

            // 右侧价格标签
            val priceAtY = if (ctx.crosshairY in padT..volTop) {
                val ratio = 1f - ((ctx.crosshairY - padT) / chartH).coerceIn(0f, 1f)
                minP + ratio * (maxP - minP)
            } else {
                k?.close ?: 0.0
            }
            val priceTagW = 52f
            context.fillStyle(Color(0xFF1976D2))
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

            // 底部日期标签
            if (k != null) {
                val dateTagW = 62f
                val dateTagX = (cx - dateTagW / 2f).coerceIn(0f, width - dateTagW)
                context.fillStyle(Color(0xFF1976D2))
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

            // 选中 K线框
            if (k != null && (interaction is InteractionState.Locked || interaction is InteractionState.Hover)) {
                context.strokeStyle(Color(0xFF1976D2))
                context.lineWidth(1.5f)
                context.beginPath()
                context.moveTo(cx - cw / 2f - 2f, py(k.high) - 2f)
                context.lineTo(cx + cw / 2f + 2f, py(k.high) - 2f)
                context.lineTo(cx + cw / 2f + 2f, py(k.low) + 2f)
                context.lineTo(cx - cw / 2f - 2f, py(k.low) + 2f)
                context.closePath()
                context.stroke()
            }

            // 区间选择遮罩
            if (interaction is InteractionState.RangeSelect) {
                val startLocal = interaction.startGlobalIdx - ctx.klineStartIndex
                val endLocal = interaction.endGlobalIdx - ctx.klineStartIndex
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

            // OHLC Tooltip（顶部浮层）
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
                // 与AI价位距离
                if (aiLevels.isNotEmpty()) {
                    val nearest = aiLevels.minByOrNull { abs(it.price - k.close) }
                    nearest?.let {
                        val dist = (k.close - it.price) / it.price * 100.0
                        context.fillStyle(Color(0xFFFFE082))
                        context.fillText("距${it.label} ${fmtSignedPct(dist)}", 4f, 34f)
                    }
                }
            }

            // 区间统计浮层（区间选择时显示）
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
                context.fillStyle(Color(0xFFB0BEC5))
                context.fillText("${stats.startDate} ~ ${stats.endDate}", 4f, statsY + 24f)
            }
        }

        // MA图例
        if (ctx.klineShowMA) {
            context.font(9f)
            context.textAlign(TextAlign.LEFT)
            var lx = 4f
            listOf(
                Triple("MA5", Color(0xFF1976D2), ma5Points.lastOrNull()?.second),
                Triple("MA10", Color(0xFFFF9800), ma10Points.lastOrNull()?.second),
                Triple("MA20", Color(0xFF7B1FA2), ma20Points.lastOrNull()?.second)
            ).forEach { (label, color, _) ->
                context.fillStyle(color)
                context.fillText(label, lx, padT - 6f)
                lx += 36f
            }
        }

        // P0: KlineFocus 覆盖层绘制（Range遮罩/Point竖线/Price虚线，4秒淡出）
        val focus = ctx.klineFocusState.focus
        if (focus != null) {
            val now = System.currentTimeMillis()
            val age = now - ctx.klineFocusState.focusSetAt
            val alpha = when {
                age < 2000 -> 1f
                age < 4000 -> 1f - (age - 2000) / 2000f
                else -> { ctx.klineFocusState.focus = null; 0f }
            }
            if (alpha > 0f) {
                fun withAlpha(c: Long, a: Float): Color {
                    val base = c and 0x00FFFFFF
                    val aa = ((0xFF * a).toInt().coerceIn(0, 255)).toLong() shl 24
                    return Color(base or aa)
                }
                fun cxOf(idx: Int): Float = step * idx + step / 2f
                when (focus) {
                    is KlineFocus.Range -> {
                        val s = visible.indexOfFirst { it.tradeDate == focus.start }
                        val e = visible.indexOfFirst { it.tradeDate == focus.end }.let { if (it < 0) s else it }
                        if (s >= 0) {
                            val lo = minOf(s, e); val hi = maxOf(s, e)
                            val x0 = cxOf(lo) - cw / 2
                            val x1 = cxOf(hi) + cw / 2
                            val rectH = volTop - padT
                            // 填充
                            context.beginPath()
                            context.moveTo(x0, padT)
                            context.lineTo(x1, padT)
                            context.lineTo(x1, padT + rectH)
                            context.lineTo(x0, padT + rectH)
                            context.lineTo(x0, padT)
                            context.fillStyle(withAlpha(focus.colorValue, 0.14f * alpha))
                            context.fill()
                            // 边框
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
                        val i = visible.indexOfFirst { it.tradeDate == focus.date }
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
                backgroundColor(0xFFEEEEEE)
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
                    color(0xFF666666)
                }
            }

            Text {
                attr {
                    text("收盘: ${fmt2(latest.close)}")
                    fontSize(11f)
                    fontWeightBold()
                    color(0xFF333333)
                    marginLeft(10f)
                }
            }

            Text {
                attr {
                    text("量: ${latest.volume.toInt()}手")
                    fontSize(11f)
                    color(0xFF666666)
                    marginLeft(10f)
                }
            }

            vif({ ctx.selectedKlineIndex >= 0 }) {
                Text {
                    attr {
                        text("已选 ${ctx.getAggregatedKline().getOrNull(ctx.selectedKlineIndex)?.tradeDate ?: ""}")
                        fontSize(11f)
                        color(0xFF1976D2)
                        marginLeft(10f)
                    }
                }
            }
        }
    }
}

// --- AI分析卡片化重构 ---

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
            vfor({ ObservableList(listOfNotNull(ctx.aiAnalysis).map { it to ctx.aiExpandedKeys.toList() }.toMutableList()) }) { (analysis, _) ->
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
                            text("💡 联动交互说明")
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
                    border(Border(1.2f, BorderStyle.SOLID, Color(0xFF1976D2)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    Text {
                        attr {
                            text("🎯 $title")
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
                            text("⚠️ $title")
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
                        text("📝 $title")
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
                    color(if (dist >= 0) 0xFFE53935 else 0xFF43A047)
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

internal fun ViewContainer<*, *>.analyzingView(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 20f, left = 16f, bottom = 20f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(12f)
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
                text("正在结合K线、指标与盘口数据生成结构化研判")
                fontSize(12f)
                color(0xFF999999)
                marginTop(6f)
                textAlignCenter()
            }
        }

        View {
            attr {
                marginTop(12f)
                flexDirectionRow()
            }
            repeat(3) { i ->
                View {
                    attr {
                        width(8f)
                        height(8f)
                        borderRadius(4f)
                        backgroundColor(0xFF1976D2)
                        marginLeft(if (i == 0) 0f else 6f)
                        opacity(if (i == 0) 1f else 0.5f)
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.notAnalyzedView(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 20f, left = 16f, bottom = 20f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(12f)
        }

        Text {
            attr {
                text("尚未进行 AI 分析")
                fontSize(14f)
                color(0xFF666666)
            }
        }

        Text {
            attr {
                text("AI 将结合K线走势、技术指标与盘口数据，生成带关键价位的结构化研判，并与K线联动")
                fontSize(11f)
                color(0xFF999999)
                marginTop(6f)
                textAlignCenter()
                lineHeight(16f)
            }
        }

        View {
            attr {
                marginTop(14f)
                padding(top = 10f, left = 28f, bottom = 10f, right = 28f)
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
    }
}

internal fun ViewContainer<*, *>.detailAlertConfirmDialog(ctx: StockDetailPage) {
    View {
        attr { absolutePositionAllZero(); backgroundColor(0x88000000); allCenter() }
        View {
            attr { width(ctx.pagerData.pageViewWidth - 46f); padding(18f); borderRadius(18f); backgroundColor(Color.WHITE) }
            Text { attr { text("确认创建价格提醒"); fontSize(18f); fontWeightBold(); color(0xFF172A43) } }
            Text { attr { text("${ctx.pendingAlertName} · ${ctx.pendingAlertCode}"); fontSize(13f); color(0xFF697789); marginTop(9f) } }
            View { attr { padding(14f); marginTop(12f); borderRadius(12f); backgroundColor(0xFFF4F7FB) }
                Text { attr { text(if (ctx.pendingAlertType == 1) "价格跌至或低于" else "价格涨至或高于"); fontSize(11f); color(0xFF7A8797) } }
                Text { attr { text("¥ ${fmt2(ctx.pendingAlertValue)}"); fontSize(23f); fontWeightBold(); color(0xFF173C64); marginTop(4f) } }
            }
            Text { attr { text("提醒在行情数据刷新时检查，可能存在延迟。"); fontSize(11f); color(0xFF8A94A1); marginTop(10f) } }
            View { attr { flexDirectionRow(); marginTop(16f) }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFFF0F2F5) }; event { click { ctx.showAlertConfirm = false } }; Text { attr { text("取消"); fontSize(13f); color(0xFF697586) } } }
                View { attr { width(10f) } }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFF0E67D1) }; event { click { ctx.confirmAlert() } }; Text { attr { text("确认创建"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } } }
            }
        }
    }
}

internal fun ViewContainer<*, *>.detailAiToast(ctx: StockDetailPage) {
    View {
        attr {
            absolutePosition(top = 70f, left = 0f, right = 0f)
            alignItems(FlexAlign.CENTER)
        }
        event { click { ctx.aiErrorNotice = "" } }
        View {
            attr {
                maxWidth(ctx.pagerData.pageViewWidth - 60f)
                backgroundColor(0xE60E67D1)
                borderRadius(10f)
                padding(left = 14f, top = 8f, right = 14f, bottom = 8f)
            }
            Text {
                attr {
                    text(ctx.aiErrorNotice)
                    fontSize(12f)
                    color(0xFFFFFFFF)
                    textAlignCenter()
                }
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
    val cards: List<Map<String, Any?>>,
    val source: String = "来源未标注",
    val generatedAt: Long = 0,
    val dataDate: String = "",
    // ---- v2 新增 ----
    val verdict: com.kuikly.stock.data.AIVerdict? = null,
    val protocolVersion: Int = 1,      // 2=严格v2, 1=旧协议降级, 0=离线模板
    val degraded: Boolean = false,
    val validateNote: String = "",
)
