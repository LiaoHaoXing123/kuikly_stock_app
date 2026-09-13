// 个股详情页：基础信息、实时行情、技术指标、分时与五档盘口，以及 AI 分析入口。
// 增强版：K线多周期/缩放/平移/MA/AI价位联动，AI分析卡片化与K线深度融合

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

    /**
     * 是否正在取数（含静默刷新）。首屏之外不再铺骨架屏，但需要一个可见的「在刷新」信号，
     * 所以和 [isLoading] 分开：isLoading 只管首屏骨架。
     */
    internal var refreshing by observable(false)

    /**
     * 内容重建世代。
     *
     * 详情页的卡片都是「构建时读一次数据」，数据变了不会自己更新；原来靠
     * isLoading 翻转把整页换成骨架屏、再换回来，顺带重建了内容。
     * 静默刷新不能再用这一招（会把页面清空），所以改由这个世代号驱动
     * Scroller 内部的内容重建：Scroller 本身留在重建范围之外，滚动位置得以保住。
     */
    internal var detailEpoch by observable(0)

    /** 行情卡「最新价 / 涨跌额 / 涨跌幅」三个数一起滚。 */
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

    /** 按压态：导航栏按钮等可点元素的按下高亮由它驱动，页面唯一一份。 */
    internal val press = PressState(this)

    /**
     * 同业卡与官方板块卡的信息高度重叠（都是「同行业个股相对强弱」），同屏展示会互相稀释。
     * 因此二者降级互斥：官方板块快照可用时以它为准，同业卡让位；
     * 官方板块缺失（无 sector_board 数据 / 该行业未收录）时才回退到本地同业样本卡。
     * 注意：industryPeers() 的数据仍照常拉取，DeepSeekApi 的行业上下文依赖它。
     */
    internal val industryCardVisible: Boolean
        get() = industrySnapshot != null && sectorSnapshot == null

    // --- K线增强状态 ---
    internal var klinePeriod by observable("D") // D=日 W=周 M=月
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
    internal var klineSubIndicator by observable("none") // none / macd / kdj / rsi，副图指标，默认关闭
    internal var klineShowTrend by observable(false) // 自动趋势线（支撑/压力）叠加，默认关闭

    // --- P1 K线交互状态 ---
    override val crosshair = CrosshairController()
    override val nativeChartGestures: Boolean get() = pagerData.params.optBoolean("nativeChartGestures", false)
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

    // 分时选点是否已「锁定」：拖动跟手时为 false，点击/抬手后为 true
    internal var minuteLocked by observable(false)

    // --- 盘口增强 ---
    internal var orderBookHighlightPrice by observable(0.0)
    internal var orderBookMode by observable("list") // list / depth

    // --- AI卡片交互 ---
    internal var aiExpandedKeys: ObservableList<String> by observableList()
    internal var aiCardHighlightKey by observable("")
    internal var reviewExpanded by observable(false) // AI 复盘卡明细展开态

    // --- P0 AI联动状态 ---
    internal var pendingFocus: KlineFocus? by observable(null)
    internal var klineVisibleRange: Pair<String, String>? by observable(null)
    internal var verdictExpanded by observable(false)
    internal var klineSectionY by observable(0f)
    internal var aiSectionY by observable(0f)
    internal var highlightCardType: String? by observable(null)
    internal var showStickyVerdict by observable(false)
    internal val klineFocusState = KlineFocusState()
    private var focusVersion = 0

    // --- 提醒确认弹窗 ---
    /** 显隐 + 入场动画绑在一起，避免「只改显隐、弹窗停在透明态」。 */
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
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        // 首屏那次 loading 在 didInit 里就发起了（那时 body 还没构建，扫光无从谈起）。
        // 页面真正上屏时补一次：若骨架屏还在，扫光就从这个帧开始转。
        skeletonPulse.bump()
        // 重新上屏时静默刷新一次：这是详情页唯一自然的刷新时机。
        // 首屏之后不再铺骨架屏，用户看不到页面被清空，只有数字在动。
        loadStockDetail()
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

                        // 内容按世代号重建：卡片都是「构建时读一次数据」，刷新后必须重建
                        // 才会显示新值。Scroller 本身留在重建范围之外，滚动位置就不会被重置。
                        // 注意 vfor 的 creator 闭包**只能产生一个孩子节点**（框架会校验），
                        // 所以这里套一层列容器把整页正文收成一个节点。
                        vfor({ ObservableList(mutableListOf(ctx.detailEpoch)) }) { _ ->
                            View {
                                attr {
                                    flexDirectionColumn()
                                    width(ctx.pagerData.pageViewWidth)
                                }
                                detailContent(ctx)
                            }
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

    /**
     * 取详情数据。
     *
     * 首屏（还没有数据）铺整页骨架屏；之后的任何一次拉取都是**静默刷新**——
     * 页面内容留在原地，只有数字与图形更新。这样价格才有「原地变化」的机会，
     * 否则每次刷新都会把承载数字的视图换成骨架屏再重建，补间无从谈起。
     */
    internal fun loadStockDetail() {
        if (stockCode.isEmpty() || refreshing) return
        val firstLoad = stockDetail == null
        refreshing = true
        if (firstLoad) {
            isLoading = true
            // 整页骨架屏刚由 vif 同步挂载，此刻拉起扫光才赶得上首帧
            skeletonPulse.bump()
        }

        lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val data = StockRepository.loadStockDetail(stockCode)
                delay(0)
                if (data != null) {
                    stockDetail = data
                    industrySnapshot = runCatching { com.kuikly.stock.data.StockDb.industryPeers(stockCode) }.getOrNull()
                    sectorSnapshot = runCatching { com.kuikly.stock.data.StockDb.sectorOfStock(stockCode) }.getOrNull()
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
                    publishQuote(firstLoad)
                } else {
                    stockDetail = null
                    loadErrorMessage = "未找到股票 $stockCode 的数据"
                }
            } catch (e: Throwable) {
                delay(0)
                stockDetail = null
                loadErrorMessage = e.message ?: "数据加载失败"
            } finally {
                // 首屏骨架保证最短展示：本地数据秒载时也不让骨架"一闪而过"
                if (firstLoad) ensureSkeletonVisible(startedAt)
                isLoading = false
                refreshing = false
            }
        }
    }

    /**
     * 把行情卡的内容推到最新：先重建内容（让新数据落到版式里），再滚动数字。
     *
     * 顺序不能反。重建时新视图先读到滚动器里的**旧值**，随后逐帧滚到新值——
     * 这正是补间能看见的原因；先滚再重建的话，新视图一出生就是终值，看不到过程。
     */
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
        // 三个点的波浪由协程按步推进，分析结束自动停（实现见 base/Anim.kt）
        aiDotWave.loop(AI_DOT_STEP_MS) { isAnalyzing }
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
        get() = aiAnalysis?.let { com.kuikly.stock.data.AiReviewEngine.verdictOf(it) }

    /** 统一联动入口：滚至 K 线并高亮目标 */
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
        skeletonPulse.bump()
        lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
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
            val startedAt = System.currentTimeMillis()
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

    // --- K线周期与视口逻辑 ---

    internal fun switchKlinePeriod(period: String) {
        if (klinePeriod == period) return
        clearInteraction()
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

    override fun getAggregatedKline(): List<KLineDataItem> {
        val original = stockDetail?.kline ?: return emptyList()
        // 周/月聚合每次都新建整段列表，而 Canvas 绘制回调每帧都要取（getVisibleKline 内部还会再取一次），
        // 所以按「源列表实例 + 周期」记忆化：行情重新加载时 stockDetail.kline 换成新实例，缓存自然失效。
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

    // ------------------------------------------------------------------
    // 派生结果缓存
    //
    // 下面这些派生物只依赖输入数据、与视口无关，但都产生在每帧执行的绘制回调里。
    // 按「输入实例」记忆化，把「每帧重算 + 每帧分配」降为「数据变化时才算」。
    // 全部用引用相等（===）做键：数据刷新时上游整体换新实例，缓存即自动失效，
    // 不需要额外的失效通知，也就不会出现「缓存没清掉显示旧值」这类问题。
    //
    // 规模说明：当前单只股票 20~400 根 K 线，单次重算本身只有微秒级，
    // 这里省下的主要是绘制线程上的重复分配（GC 压力），不是墙钟时间。
    // ------------------------------------------------------------------

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

    /** 副图 MACD：同一份聚合结果只算一次。 */
    internal fun macdOf(agg: List<KLineDataItem>): MacdSeries {
        resetIndicatorCacheIfNeeded(agg)
        return indCacheMacd ?: computeMACD(agg.map { it.close }).also { indCacheMacd = it }
    }

    /** 副图 KDJ：同一份聚合结果只算一次。 */
    internal fun kdjOf(agg: List<KLineDataItem>): KdjSeries {
        resetIndicatorCacheIfNeeded(agg)
        return indCacheKdj ?: computeKDJ(
            agg.map { it.high }, agg.map { it.low }, agg.map { it.close }
        ).also { indCacheKdj = it }
    }

    /** 副图 RSI：同一份聚合结果只算一次。 */
    internal fun rsiOf(agg: List<KLineDataItem>): RsiSeries {
        resetIndicatorCacheIfNeeded(agg)
        return indCacheRsi ?: computeRSI(agg.map { it.close }).also { indCacheRsi = it }
    }

    private var trendCacheSource: List<KLineDataItem>? = null
    private var trendCacheStart = -1
    private var trendCacheCount = -1
    private var trendCacheValue: TrendLines = TrendLines(null, null)

    /** 趋势线只依赖可见区间；键为「聚合实例 + 视口起止」，缩放平移之外不动它就是命中。 */
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

    /** AI 价位线：解析要遍历 cards 并兼容多种字段名，同样按 analysis 实例缓存。 */
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
        if (agg.isEmpty()) return emptyList()
        val start = klineStartIndex.coerceIn(0, (agg.size - 1).coerceAtLeast(0))
        val end = (start + klineVisibleCount).coerceAtMost(agg.size)
        return if (start >= end) emptyList() else agg.subList(start, end)
    }

    /** 缩放/平移动画序号：每次新动画自增，旧动画检测到变化即自行放弃，避免连点打架。 */
    private var chartAnimSeq = 0

    internal fun zoomIn() = animateZoom(-5)

    internal fun zoomOut() = animateZoom(+5)

    internal fun panLeft() = animatePan(-5)

    internal fun panRight() = animatePan(+5)

    /** 缩放视口并保持中心 K 线不动，带缓动。 */
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

    /** 平移视口，带缓动。 */
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

    /** P1: 点击十字光标（锁定/解锁） */
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

    /** P1: 开始区间选择 */
    override fun beginRangeSelect(x: Float) {
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onRangeStart(globalIdx)
        isRangeSelecting = true
        crosshairX = x
    }

    /** P1: 更新区间选择 */
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

    /** P1: 结束区间选择 */
    override fun endRangeSelect() {
        crosshair.onRangeEnd()
        isRangeSelecting = false
        if (crosshair.state is InteractionState.Locked) {
            val idx = (crosshair.state as InteractionState.Locked).globalIdx
            selectedKlineIndex = idx
        }
    }

    /** P1: 清除所有交互状态 */
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
        // 更新信息文本
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
    override fun selectMinuteAtX(x: Float, locked: Boolean) {
        val data = minuteData ?: return
        if (data.isEmpty() || minuteCanvasWidth <= 0f) return
        val idx = ((x / minuteCanvasWidth) * data.size).toInt().coerceIn(0, data.size - 1)
        // 点击已锁定的同一点 → 取消选中（与 K 线十字光标的心智一致）
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

    override fun clearMinuteSelection() {
        if (highlightedPriceLabel.startsWith("分时 ")) clearHighlight()
        selectedMinuteIndex = -1
        minuteInfoText = ""
        minuteLocked = false
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
        alertOverlay.show()
    }

    /** 关闭确认弹窗（卸载与入场脉冲归位由 Overlay 一并处理）。 */
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

/** 详情页正文（Scroller 的全部内容）。由 detailEpoch 驱动整段重建。 */
private fun ViewContainer<*, *>.detailContent(ctx: StockDetailPage) {
    realtimeCard(ctx)
    industryCard(ctx)
    sectorCard(ctx)
    eventCard(ctx)
    View {
        event { layoutFrameDidChange { frame -> ctx.chartAnchorY = frame.y } }
        klineChartArea(ctx)
    }
    minuteCard(ctx)
    orderBookCard(ctx)
    indicatorCard(ctx)
    fundFlowCard(ctx)
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
    infoCard(ctx)
    vif({ ctx.dataSourceText.isNotEmpty() }) {
        dataSourceFooter(ctx)
    }
}
