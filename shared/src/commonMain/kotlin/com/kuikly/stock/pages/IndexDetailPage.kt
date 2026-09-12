// 指数详情页：基础信息、实时点位、日K走势，以及 AI 解读入口。
// 增强版：K线多周期/缩放/平移/AI价位联动，AI分析卡片化

package com.kuikly.stock.pages

import com.kuikly.stock.ui.component.AI_DOT_STEP_MS
import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ui.component.PRESS_BG_DARK
import com.kuikly.stock.ui.component.PRESS_BG_NONE
import com.kuikly.stock.ui.component.PressState
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedBg
import com.kuikly.stock.ui.component.pressedScale
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.kuikly.stock.ui.component.NumberRoll
import com.kuikly.stock.ui.component.aiDotWaveDots
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
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuiklybase.KuiklyMarkdown
import com.kuikly.stock.data.fmt0
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSigned2
import com.kuikly.stock.data.fmtSignedPct
import kotlin.math.abs
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.openModule
import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.component.chartControlButton
import com.kuikly.stock.ui.component.quoteItem

@Page("index_detail")
class IndexDetailPage : BasePager(), KlineInteractionHost {

    internal var indexCode by observable("")
    internal var indexDetail by observable<StockDetailData?>(null)
    internal val analysisState = DetailAnalysisState("index")
    internal var aiAnalysis: AIAnalysisData?
        get() = analysisState.analysis
        set(value) { analysisState.analysis = value }
    internal var detailScrollerRef: ViewRef<ScrollerView<*, *>>? = null
    internal var chartAnchorY = 0f
    internal var isLoading by observable(true)

    /** 是否正在取数（含静默刷新）。首屏之外不铺骨架屏，所以和 [isLoading] 分开。 */
    internal var refreshing by observable(false)

    /**
     * 内容重建世代。卡片都是「构建时读一次数据」，静默刷新时靠它驱动重建；
     * Scroller 留在重建范围之外，滚动位置得以保住。原因详见个股详情页同名字段。
     */
    internal var detailEpoch by observable(0)

    /** 「最新点位 / 涨跌点 / 涨跌幅」三个数一起滚。 */
    internal val quoteRoll = NumberRoll(this)
    internal var isAnalyzing by observable(false)

    /** 按压态：顶栏可点元素共用。 */
    internal val press = PressState(this)
    internal var loadErrorMessage by observable("")
    internal var dataSourceText by observable("")
    override var selectedKlineIndex by observable(-1)
    override var klineCanvasWidth by observable(0f)

    // K线增强
    internal var klinePeriod by observable("D")
    override var klineVisibleCount by observable(30)
    override var klineStartIndex by observable(0)
    internal var highlightedPrice by observable(0.0)
    internal var highlightedPriceLabel by observable("")
    internal var klineInfoText by observable("")
    internal var aiExpandedKeys: ObservableList<String> by observableList()
    internal var aiToast by observable("")

    // --- K线交互状态（与个股页共用 chartTouchLayer / KlineInteractionHost） ---
    override val crosshair = CrosshairController()
    override val nativeChartGestures: Boolean get() = pagerData.params.optBoolean("nativeChartGestures", false)
    internal var crosshairX by observable(-1f)
    internal var crosshairY by observable(-1f)
    internal var rangeStats: RangeStats? by observable(null)
    internal var isRangeSelecting by observable(false)

    override fun didInit() {
        super.didInit()
        indexCode = pagerData.params.optString("code", "")
        analysisState.restore(indexCode)

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
                    backgroundColor(AppColor.SURFACE_SOFT)
                }

                vif({ ctx.isLoading }) {
                    stockDetailLoadingView(ctx)
                }
                velseif({ ctx.indexDetail == null }) {
                    indexErrorView(ctx)
                }
                velse {
                    indexNavigationBar(ctx)

                    Scroller {
                        ref { ctx.detailScrollerRef = it }
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            scrollEnable(true)
                        }

                        // 内容按世代号重建，Scroller 本身不重建 → 静默刷新时滚动位置不变。
                        // vfor 的 creator 闭包只能产生一个孩子节点，故套一层列容器收拢。
                        vfor({ ObservableList(mutableListOf(ctx.detailEpoch)) }) { _ ->
                            View {
                                attr {
                                    flexDirectionColumn()
                                    width(ctx.pagerData.pageViewWidth)
                                }
                                indexDetailContent(ctx)
                            }
                        }
                    }
                }

                vif({ ctx.aiToast.isNotEmpty() }) {
                    indexAiToast(ctx)
                }
            }
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        // 首屏 loading 在 didInit 里就发起了（那时 body 还没构建）。
        // 页面真正上屏时补一次：若骨架屏还在，扫光就从这个帧开始转。
        skeletonPulse.bump()
        // 重新上屏时静默刷新一次（首屏那次刷新请求还在跑则自动跳过）
        loadIndexDetail()
    }

    /**
     * 取指数详情。首屏铺整页骨架屏，之后的任何一次拉取都是静默刷新
     * （页面内容留在原地，只有数字与图形更新）——与个股详情页保持一致。
     */
    internal fun loadIndexDetail() {
        if (indexCode.isEmpty() || refreshing) return
        val firstLoad = indexDetail == null
        refreshing = true
        if (firstLoad) {
            isLoading = true
            // 骨架屏刚由 vif 同步挂载，此刻拉起扫光才赶得上首帧
            skeletonPulse.bump()
        }

        lifecycleScope.launch {
            try {
                val data = StockRepository.loadIndexDetail(indexCode)
                delay(0)
                if (data != null) {
                    indexDetail = data
                    val total = data.kline?.size ?: 0
                    klineVisibleCount = when {
                        total >= 60 -> 30
                        total >= 30 -> total
                        else -> total
                    }
                    klineStartIndex = (total - klineVisibleCount).coerceAtLeast(0)
                    publishQuote(firstLoad)
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
                refreshing = false
            }
        }
    }

    /**
     * 先重建内容（新数据落进版式），再滚动数字——顺序不能反，
     * 否则新视图一出生就是终值，看不到滚动过程。
     */
    private fun publishQuote(firstLoad: Boolean) {
        val rt = indexDetail?.realtime
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
        // 三点波浪由协程按步推进（数字/透明度属性动画只在值变化时才会走动画路径，
        // 而波浪本身就需要不断变化的值），分析结束自动停。
        aiDotWave.loop(AI_DOT_STEP_MS) { isAnalyzing }
        lifecycleScope.launch {
            try {
                val result = StockRepository.analyzeIndex(indexCode)
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
                aiToast = analysisState.notice
            } finally {
                isAnalyzing = false
            }
        }
    }

    // K线逻辑复用个股页聚合函数
    override fun getAggregatedKline(): List<KLineDataItem> {
        val original = indexDetail?.kline ?: return emptyList()
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

    internal fun switchKlinePeriod(period: String) {
        if (klinePeriod == period) return
        klinePeriod = period
        // 周期已切换，getAggregatedKline() 返回的就是新聚合，无需再算一遍
        val newAgg = getAggregatedKline()
        val newTotal = newAgg.size
        klineVisibleCount = when (period) {
            "W" -> 26.coerceAtMost(newTotal)
            "M" -> 12.coerceAtMost(newTotal)
            else -> 30.coerceAtMost(newTotal)
        }
        klineStartIndex = (newTotal - klineVisibleCount).coerceAtLeast(0)
        selectedKlineIndex = -1
        klineInfoText = if (period == "D") "日K" else if (period == "W") "周K · 自然周聚合" else "月K · 按月聚合"
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

    internal fun resetView() {
        clearChartSelection()
        val total = getAggregatedKline().size
        klineStartIndex = (total - klineVisibleCount).coerceAtLeast(0)
        selectedKlineIndex = -1
        highlightedPrice = 0.0
        highlightedPriceLabel = ""
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
    }

    // ---- 十字光标 / 区间框选（与个股页一致，供 chartTouchLayer 调用） ----
    override fun updateCrosshair(x: Float, y: Float) {
        crosshairX = x
        crosshairY = y
        val agg = getAggregatedKline()
        if (agg.isEmpty() || klineCanvasWidth <= 0f) return
        val globalIdx = (klineStartIndex + chartHitIndex(x, klineCanvasWidth, getVisibleKline().size)).coerceIn(0, agg.size - 1)
        crosshair.onMove(globalIdx)
        agg.getOrNull(globalIdx)?.let { k ->
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
        val locked = crosshair.state is InteractionState.Locked
        selectedKlineIndex = if (locked) globalIdx else -1
        crosshairX = if (locked) x else -1f
        if (!locked) {
            klineInfoText = ""
            rangeStats = null
        } else {
            agg.getOrNull(globalIdx)?.let { k ->
                val changePct = if (k.open != 0.0) (k.close - k.open) / k.open * 100.0 else 0.0
                klineInfoText = "${k.tradeDate} 开${fmt2(k.open)} 收${fmt2(k.close)} 高${fmt2(k.high)} 低${fmt2(k.low)} ${fmtSignedPct(changePct)}"
            }
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
        (crosshair.state as? InteractionState.RangeSelect)?.let {
            rangeStats = summarizeRange(agg, it.startGlobalIdx, it.endGlobalIdx)
        }
    }

    override fun endRangeSelect() {
        crosshair.onRangeEnd()
        isRangeSelecting = false
        (crosshair.state as? InteractionState.Locked)?.let { selectedKlineIndex = it.globalIdx }
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

    /** 指数页无分时图，接口空实现 */
    override fun selectMinuteAtX(x: Float, locked: Boolean) {}

    override fun clearMinuteSelection() {}

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
        val prompt = detailFollowupPrompt("index", indexCode, indexDetail?.info?.name ?: indexCode, klinePeriod, bars, selected, aiAnalysis)
        val params = JSONObject()
        params.put("detail_question", prompt)
        openModule(AppRoutes.CHAT, params)
    }

    internal fun highlightAIPrice(price: Double, label: String) {
        if (price <= 0 || !price.isFinite()) return
        highlightedPrice = price
        highlightedPriceLabel = label
        scrollToChart()
        aiToast = "已在K线标注 $label ${fmt2(price)}"
        lifecycleScope.launch {
            delay(2000)
            if (aiToast.contains("已在K线标注")) aiToast = ""
        }
    }

    internal fun clearHighlight() {
        highlightedPrice = 0.0
        highlightedPriceLabel = ""
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
}

internal fun ViewContainer<*, *>.indexNavigationBar(ctx: IndexDetailPage) {
    val name = ctx.indexDetail?.info?.name ?: "指数"
    val code = ctx.indexDetail?.info?.code ?: ctx.indexCode

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.SUCCESS)
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
                    color(AppColor.ON_DARK)
                }
            }
        }

        Text {
            attr {
                text(name)
                fontSize(16f)
                fontWeightBold()
                color(AppColor.ON_DARK)
                marginLeft(8f)
            }
        }

        Text {
            attr {
                text("($code)")
                fontSize(12f)
                color(AppColor.SUCCESS_LINE)
                marginLeft(4f)
            }
        }

        View { attr { flex(1f) } }

        // 手动刷新：静默刷新（不铺骨架屏），刷新期间按钮自身就是进度指示
        View {
            attr {
                padding(8f, 8f, 8f, 8f)
                borderRadius(8f)
                pressedBg(ctx.press, INDEX_NAV_REFRESH_TAG, normal = PRESS_BG_NONE, pressed = PRESS_BG_DARK)
                pressedScale(ctx.press, INDEX_NAV_REFRESH_TAG, pressed = 0.94f)
                accessibility(if (ctx.refreshing) "正在刷新行情" else "刷新行情")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(!ctx.refreshing, false)
            }
            event {
                pressFeedback(ctx.press, INDEX_NAV_REFRESH_TAG)
                click {
                    ctx.press.releaseAll()
                    if (!ctx.refreshing) ctx.loadIndexDetail()
                }
            }
            Text {
                attr {
                    text(if (ctx.refreshing) "…" else "↻")
                    fontSize(17f)
                    fontWeightBold()
                    color(if (ctx.refreshing) AppColor.SUCCESS_LINE else AppColor.ON_DARK)
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
                    color(AppColor.ON_DARK)
                }
            }
        }
    }
}

private const val INDEX_NAV_REFRESH_TAG = "index_nav_refresh"

internal fun ViewContainer<*, *>.indexInfoCard(ctx: IndexDetailPage) {
    val info = ctx.indexDetail?.info ?: return

    View {
        attr {
            flexDirectionColumn()
            margin(8f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        Text {
            attr {
                text("基础信息")
                fontSize(15f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
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
        pct == null || pct == 0.0 -> AppColor.TEXT_HINT
        pct > 0 -> StockColors.UP
        else -> StockColors.DOWN
    }

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        Text {
            attr {
                text("实时行情")
                fontSize(15f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
                marginBottom(8f)
            }
        }

        View {
            attr {
                flexDirectionRow()
                marginBottom(8f)
            }

            // 读滚动器（lambda 内读，才能拿到中间帧），静默刷新时逐帧滚到新值
            val hasPrice = realtime.price != null
            indexQuoteColumn("最新点位",
                { if (hasPrice) fmt2(ctx.quoteRoll.value(0)) else "-" },
                26f, priceColor)
            indexQuoteColumn("涨跌点",
                { if (hasPrice) fmtSigned2(ctx.quoteRoll.value(1)) else "-" },
                15f, priceColor)
            indexQuoteColumn("涨跌幅",
                { if (hasPrice) fmtSignedPct(ctx.quoteRoll.value(2)) else "-" },
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

/**
 * 指数页的行情小格。宽度算式比个股页少扣一点（指数卡没有额外涨跌条占位）。
 */
internal fun ViewContainer<*, *>.indexQuoteItem(
    pageViewWidth: Float,
    label: String,
    value: Double?,
    format: ((Double) -> String)? = null
) {
    quoteItem(
        label = label,
        valueText = value?.let { format?.invoke(it) ?: fmt2(it) } ?: "-",
        width = (pageViewWidth - 40f) / 4f,
        marginTop = 4f,
    )
}

internal fun ViewContainer<*, *>.indexQuoteColumn(
    label: String,
    value: () -> String,
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
                color(AppColor.TEXT_HINT)
            }
        }

        Text {
            attr {
                text(value())
                fontSize(valueSize)
                fontWeightBold()
                color(color)
                marginTop(2f)
            }
        }
    }
}

internal fun fmtIndexVolume(v: Double): String = when {
    v >= 100000000 -> fmt2(v / 100000000) + "亿股"
    v >= 10000 -> fmt2(v / 10000) + "万股"
    else -> fmt0(v) + "股"
}

internal fun fmtIndexAmount(v: Double): String = when {
    v >= 100000000 -> fmt2(v / 100000000) + "亿元"
    v >= 10000 -> fmt2(v / 10000) + "万元"
    else -> fmt0(v) + "元"
}

internal fun ViewContainer<*, *>.indexDataSourceFooter(ctx: IndexDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 10f, left = 16f, bottom = 10f, right = 16f)
            backgroundColor(AppColor.SURFACE_ALT)
            borderRadius(8f)
        }

        Text {
            attr {
                text("数据来源")
                fontSize(11f)
                color(AppColor.TEXT_HINT)
                marginBottom(4f)
            }
        }

        Text {
            attr {
                text(ctx.dataSourceText)
                fontSize(11f)
                color(AppColor.TEXT_HINT_SOFT)
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexKlineChartArea(ctx: IndexDetailPage) {
    val original = ctx.indexDetail?.kline

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
                    text(ctx.klineInfoText.ifEmpty { "${ctx.getAggregatedKline().size}根 · ${ctx.klineVisibleCount}显示" })
                    fontSize(10f)
                    color(AppColor.TEXT_HINT)
                    flex(1f)
                    textAlignRight()
                }
            }
        }

        View {
            attr { flexDirectionRow(); marginBottom(8f) }
            indexPeriodChip(ctx, "D", "日K")
            indexPeriodChip(ctx, "W", "周K")
            indexPeriodChip(ctx, "M", "月K")
            View { attr { flex(1f) } }
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(AppColor.SURFACE_SOFT)
                    borderRadius(10f)
                }
                event { click { ctx.resetView() } }
                Text { attr { text("重置"); fontSize(11f); color(AppColor.TEXT_GRAY) } }
            }
        }

        vif({ ctx.isLoading }) {
            klineLoadingView(ctx)
        }
        velseif({ original != null && original.isNotEmpty() }) {
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
                View { attr { flex(1f) } }
                vif({ ctx.highlightedPrice > 0 }) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItems(FlexAlign.CENTER)
                            backgroundColor(AppColor.SUCCESS_BG)
                            borderRadius(8f)
                            padding(3f, 8f, 3f, 8f)
                        }
                        Text {
                            attr {
                                text("${ctx.highlightedPriceLabel} ${fmt2(ctx.highlightedPrice)}")
                                fontSize(11f)
                                color(AppColor.SUCCESS)
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

            vfor({ ObservableList(mutableListOf(listOf(ctx.getAggregatedKline(), ctx.klineStartIndex, ctx.klineVisibleCount, ctx.selectedKlineIndex, ctx.aiAnalysis, ctx.highlightedPrice, ctx.highlightedPriceLabel, ctx.klinePeriod))) }) { _ ->
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
                    indexKlineChartCanvas(ctx, ctx.getAggregatedKline())
                    chartTouchLayer(ctx)
                }
                indexKlineSummary(ctx, ctx.getVisibleKline())
            }
            }
            chartEvidencePanel({ ctx.getAggregatedKline() }, { ctx.selectedKlineIndex }, { ctx.aiAnalysis }, { ctx.focusCandle(it) }, { ctx.askAboutChart() })

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
                    text("点击/长按K线查看详情 · 缩放平移查看历史 · 点击AI价位联动标注")
                    fontSize(10f)
                    color(AppColor.DISABLED)
                    marginTop(6f)
                }
            }
        }
        velse {
            indexKlineErrorView(ctx)
        }
    }
}

internal fun ViewContainer<*, *>.indexPeriodChip(ctx: IndexDetailPage, period: String, label: String) {
    View {
        attr {
            padding(5f, 12f, 5f, 12f)
            backgroundColor(if (ctx.klinePeriod == period) AppColor.SUCCESS else AppColor.SURFACE_SOFT)
            borderRadius(14f)
            marginRight(6f)
        }
        event { click { ctx.switchKlinePeriod(period) } }
        Text {
            attr {
                text(label)
                fontSize(12f)
                fontWeightBold()
                color(if (ctx.klinePeriod == period) AppColor.ON_DARK else AppColor.TEXT_GRAY)
            }
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
                color(AppColor.TEXT_HINT)
                textAlignCenter()
            }
        }
        View {
            attr {
                marginTop(12f)
                padding(top = 8f, left = 20f, bottom = 8f, right = 20f)
                backgroundColor(AppColor.SUCCESS_BG)
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
                    color(AppColor.SUCCESS)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexKlineChartCanvas(ctx: IndexDetailPage, aggregated: List<KLineDataItem>) {
    val visible = ctx.getVisibleKline()
    val aiLevels = parseAIPriceLevels(ctx.aiAnalysis)

    Canvas({
        attr {
            height(360f)
            marginTop(2f)
        }
        // 手势统一由外层 chartTouchLayer 处理（点选锁定 / 长按框选 / 平移 / 缩放）
    }) { context, width, height ->
        val nTotal = aggregated.size
        val nVisible = visible.size
        if (nTotal == 0 || nVisible == 0 || width <= 0f || height <= 0f) return@Canvas

        if (ctx.klineCanvasWidth != width) {
            ctx.klineCanvasWidth = width
        }

        val tooltipH = 36f
        val padT = 38f
        val volTop = height - 70f
        val volH = 44f
        val dateY = height - 10f

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

        // AI levels
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
            context.strokeStyle(Color(AppColor.SUCCESS))
            context.lineWidth(2f)
            context.beginPath()
            context.moveTo(0f, y)
            context.lineTo(width, y)
            context.stroke()
            context.fillStyle(Color(AppColor.SUCCESS))
            context.font(10f)
            context.textAlign(TextAlign.LEFT)
            context.fillText("★ ${ctx.highlightedPriceLabel} ${fmt2(ctx.highlightedPrice)}", 4f, y - 4f)
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

        context.fillStyle(Color(AppColor.TEXT_HINT))
        context.font(10f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(maxP), 4f, padT + 9f)
        context.fillText(fmt2(minP), 4f, volTop - 6f)

        if (visible.isNotEmpty()) {
            context.font(9f)
            context.textAlign(TextAlign.LEFT)
            context.fillText(visible.first().tradeDate, 2f, dateY)
            context.textAlign(TextAlign.RIGHT)
            context.fillText(visible.last().tradeDate, width - 2f, dateY)
        }

        val sel = ctx.selectedKlineIndex
        if (sel >= 0 && sel >= ctx.klineStartIndex && sel < ctx.klineStartIndex + nVisible) {
            val localIdx = sel - ctx.klineStartIndex
            if (localIdx in visible.indices) {
                val k = visible[localIdx]
                val cx = step * localIdx + step / 2f
                val cy = py(k.close)
                context.strokeStyle(Color(AppColor.TEXT_INK))
                context.lineWidth(1f)
                var vx = 0f
                while (vx < height) {
                    context.beginPath()
                    context.moveTo(cx, vx)
                    context.lineTo(cx, (vx + 4f).coerceAtMost(volTop))
                    context.stroke()
                    vx += 8f
                }
                var hx = 0f
                while (hx < width) {
                    context.beginPath()
                    context.moveTo(hx, cy)
                    context.lineTo((hx + 4f).coerceAtMost(width), cy)
                    context.stroke()
                    hx += 8f
                }
                context.strokeStyle(Color(AppColor.SUCCESS))
                context.lineWidth(1.5f)
                context.beginPath()
                context.moveTo(cx - cw / 2f - 2f, py(k.high) - 2f)
                context.lineTo(cx + cw / 2f + 2f, py(k.high) - 2f)
                context.lineTo(cx + cw / 2f + 2f, py(k.low) + 2f)
                context.lineTo(cx - cw / 2f - 2f, py(k.low) + 2f)
                context.closePath()
                context.stroke()

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
                context.fillStyle(Color(AppColor.ON_DARK))
                context.fillText(
                    "${k.tradeDate} 开${fmt2(k.open)} 收${fmt2(k.close)} 高${fmt2(k.high)} 低${fmt2(k.low)}",
                    4f, 12f
                )
                context.fillStyle(tooltipColor)
                context.fillText(
                    "涨跌 ${fmtSignedPct(pct)} 量${fmtIndexVolume(k.volume)}",
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
        }

        // 区间框选遮罩（长按拖选）
        (ctx.crosshair.state as? InteractionState.RangeSelect)?.let { rs ->
            val startLocal = rs.startGlobalIdx - ctx.klineStartIndex
            val endLocal = rs.endGlobalIdx - ctx.klineStartIndex
            if (startLocal in visible.indices || endLocal in visible.indices) {
                val lo = minOf(startLocal, endLocal).coerceIn(0, nVisible - 1)
                val hi = maxOf(startLocal, endLocal).coerceIn(0, nVisible - 1)
                val x0 = step * lo
                val x1 = step * (hi + 1)
                context.fillStyle(Color(0x1A1976D2))
                context.beginPath()
                context.moveTo(x0, padT); context.lineTo(x1, padT)
                context.lineTo(x1, volTop); context.lineTo(x0, volTop)
                context.closePath(); context.fill()
                context.strokeStyle(Color(0x801976D2)); context.lineWidth(1f)
                context.beginPath()
                context.moveTo(x0, padT); context.lineTo(x0, volTop)
                context.moveTo(x1, padT); context.lineTo(x1, volTop)
                context.stroke()
            }
        }

        // 区间统计浮层
        ctx.rangeStats?.let { stats ->
            val statsH = 28f
            val statsY = padT + 4f
            context.fillStyle(Color(0xE622263F))
            context.beginPath()
            context.moveTo(0f, statsY); context.lineTo(width, statsY)
            context.lineTo(width, statsY + statsH); context.lineTo(0f, statsY + statsH)
            context.closePath(); context.fill()
            context.fillStyle(Color(0xFFFFE082)); context.font(9f); context.textAlign(TextAlign.LEFT)
            context.fillText("区间统计: ${stats.summary}", 4f, statsY + 12f)
            context.fillStyle(Color(0xFFB0BEC5))
            context.fillText("${stats.startDate} ~ ${stats.endDate}", 4f, statsY + 24f)
        }
    }
}

internal fun ViewContainer<*, *>.indexKlineSummary(ctx: IndexDetailPage, visible: List<KLineDataItem>) {
    val latest = visible.lastOrNull() ?: ctx.indexDetail?.kline?.lastOrNull()
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
                    text("量: ${fmtIndexVolume(latest.volume)}")
                    fontSize(11f)
                    color(AppColor.TEXT_GRAY)
                    marginLeft(10f)
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
                        backgroundColor(AppColor.SUCCESS)
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
            indexAnalyzingView(ctx)
        }
        velseif({ ctx.aiAnalysis == null }) {
            indexNotAnalyzedView(ctx)
        }
        velse {
            vfor({ ObservableList(listOfNotNull(ctx.aiAnalysis).map { it to ctx.aiExpandedKeys.toList() }.toMutableList()) }) { (analysis, _) ->
            View {
                attr { flexDirectionColumn() }
                aiEvidencePanel({ ctx.aiAnalysis }) { ctx.focusEvidenceDate(it) }
                val orderedTypes = listOf("trend_card", "signal_card", "suggestion_card", "risk_card", "summary_card")
                val grouped = analysis.cards.filter { it["type"] != "evidence_card" }.groupBy { it["type"] as? String ?: "unknown" }
                orderedTypes.forEach { t ->
                    grouped[t]?.forEachIndexed { idx, card ->
                        indexRenderAICard(ctx, card, "${t}_$idx")
                    }
                }
                grouped.filterKeys { it !in orderedTypes }.values.flatten().forEachIndexed { idx, card ->
                    indexRenderAICard(ctx, card, "other_$idx")
                }

                View {
                    attr {
                        flexDirectionColumn()
                        marginTop(10f)
                        padding(10f, 12f, 10f, 12f)
                        backgroundColor(AppColor.SUCCESS_BG)
                        borderRadius(10f)
                    }
                    Text {
                        attr {
                            text("指数联动说明")
                            fontSize(12f)
                            fontWeightBold()
                            color(AppColor.SUCCESS)
                        }
                    }
                    Text {
                        attr {
                            text("· 点击AI点位 → K线标注\n· 点击K线 → 查看与AI点位的距离\n· 周K/月K 聚合看大势")
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

internal fun ViewContainer<*, *>.indexRenderAICard(ctx: IndexDetailPage, card: Map<String, Any?>, key: String) {
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
                    backgroundColor(AppColor.SURFACE)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1f, BorderStyle.SOLID, Color(AppColor.SUCCESS_LINE)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    View { attr { width(4f); height(16f); backgroundColor(AppColor.SUCCESS); borderRadius(2f); marginRight(8f) } }
                    Text { attr { text(title); fontSize(14f); fontWeightBold(); color(AppColor.SUCCESS); flex(1f) } }
                    View {
                        attr { padding(3f, 8f, 3f, 8f); backgroundColor(AppColor.SUCCESS_BG); borderRadius(10f) }
                        event { click { ctx.toggleAISection(key) } }
                        Text { attr { text(if (ctx.isAIExpanded(key)) "收起" else "展开"); fontSize(11f); color(AppColor.SUCCESS) } }
                    }
                }
                Text { attr { text(content); fontSize(13f); color(AppColor.TEXT_INK); marginTop(8f); lineHeight(19f) } }
            }
        }
        "signal_card" -> {
            val signals = (card["signals"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(AppColor.WARNING_BG)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    Text { attr { text("$title (${signals.size})"); fontSize(14f); fontWeightBold(); color(AppColor.WARNING_TEXT); flex(1f) } }
                    View {
                        attr { padding(3f, 8f, 3f, 8f); backgroundColor(AppColor.SURFACE); borderRadius(10f) }
                        event { click { ctx.toggleAISection(key) } }
                        Text { attr { text(if (ctx.isAIExpanded(key)) "收起" else "展开"); fontSize(11f); color(AppColor.WARNING_TEXT) } }
                    }
                }
                val displaySignals = if (ctx.isAIExpanded(key)) signals else signals.take(2)
                displaySignals.forEach { sig ->
                    View {
                        attr { flexDirectionRow(); marginTop(6f) }
                        Text { attr { text("•"); fontSize(13f); color(AppColor.WARNING_TEXT); width(14f) } }
                        Text { attr { text(sig); fontSize(12f); color(AppColor.TEXT_WARM); flex(1f); lineHeight(17f) } }
                    }
                }
            }
        }
        "suggestion_card" -> {
            val suggestion = card["suggestion"] as? String ?: "-"
            val targetPrice = parseCardPrice(card["target_price"])
            val stopLoss = parseCardPrice(card["stop_loss"])
            val support = parseCardPrice(card["support_price"] ?: card["support_value"])
            val resistance = parseCardPrice(card["resistance_price"] ?: card["resistance_value"])
            val currentPrice = ctx.indexDetail?.realtime?.price ?: 0.0

            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(AppColor.SURFACE)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                    border(Border(1.2f, BorderStyle.SOLID, Color(AppColor.SUCCESS)))
                }
                View {
                    attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                    Text { attr { text("$title"); fontSize(14f); fontWeightBold(); color(AppColor.SUCCESS); flex(1f) } }
                    View {
                        attr { padding(4f, 10f, 4f, 10f); backgroundColor(AppColor.SUCCESS); borderRadius(12f) }
                        event { click { ctx.toggleAISection(key) } }
                        Text { attr { text(if (ctx.isAIExpanded(key)) "收起详情" else "展开点位"); fontSize(11f); color(AppColor.ON_DARK); fontWeightBold() } }
                    }
                }
                Text { attr { text(suggestion); fontSize(13f); color(AppColor.TEXT_INK); marginTop(8f); lineHeight(19f) } }

                View {
                    attr { flexDirectionColumn(); marginTop(10f) }
                    if (resistance != null) indexPriceLevelRow(ctx, "压力位", resistance, currentPrice, AppColor.UP_ALT)
                    if (support != null) indexPriceLevelRow(ctx, "支撑位", support, currentPrice, AppColor.DOWN_ALT)
                    if (targetPrice != null) indexPriceLevelRow(ctx, "目标点位", targetPrice, currentPrice, AppColor.PRIMARY)
                    if (stopLoss != null) indexPriceLevelRow(ctx, "止损点位", stopLoss, currentPrice, AppColor.WARNING_TEXT)
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
                    Text { attr { text("$title"); fontSize(14f); fontWeightBold(); color(AppColor.DANGER); flex(1f) } }
                    if (riskLevel.isNotEmpty()) {
                        View {
                            attr { padding(3f, 8f, 3f, 8f); backgroundColor(AppColor.DANGER); borderRadius(10f) }
                            Text { attr { text(riskLevel); fontSize(11f); color(AppColor.ON_DARK); fontWeightBold() } }
                        }
                    }
                }
                if (content.isNotBlank()) {
                    Text { attr { text(content); fontSize(12f); color(AppColor.TEXT_WARM); marginTop(6f); lineHeight(17f) } }
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
                    backgroundColor(AppColor.SUCCESS_BG)
                    borderRadius(12f)
                    padding(12f, 14f, 12f, 14f)
                }
                Text { attr { text("$title"); fontSize(14f); fontWeightBold(); color(AppColor.SUCCESS) } }
                Text { attr { text(summary); fontSize(13f); color(AppColor.SUCCESS); marginTop(6f); lineHeight(19f) } }
            }
        }
        else -> {
            val content = card["content"] as? String ?: card.toString()
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(8f)
                    backgroundColor(AppColor.SURFACE)
                    borderRadius(10f)
                    padding(12f)
                }
                Text { attr { text(title); fontSize(13f); fontWeightBold(); color(AppColor.TEXT_INK) } }
                Text { attr { text(content); fontSize(12f); color(AppColor.TEXT_GRAY); marginTop(4f) } }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexPriceLevelRow(
    ctx: IndexDetailPage,
    label: String,
    price: Double,
    currentPrice: Double,
    color: Long
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
        View { attr { width(6f); height(6f); borderRadius(3f); backgroundColor(color); marginRight(8f) } }
        Text { attr { text(label); fontSize(12f); color(AppColor.TEXT_GRAY); width(60f) } }
        Text { attr { text(fmt2(price)); fontSize(13f); fontWeightBold(); color(color); flex(1f) } }
        if (distText.isNotEmpty()) {
            Text { attr { text(distText); fontSize(11f); color(if (dist >= 0) StockColors.UP else StockColors.DOWN); marginRight(8f) } }
        }
        View {
            attr { padding(4f, 8f, 4f, 8f); backgroundColor(AppColor.SUCCESS_BG); borderRadius(10f) }
            event { click { ctx.highlightAIPrice(price, label) } }
            Text { attr { text("标注"); fontSize(10f); color(AppColor.SUCCESS); fontWeightBold() } }
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
                backgroundColor(AppColor.SUCCESS_BG)
                borderRadius(12f)
                padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            }
            KuiklyMarkdown(content = sanitizeMarkdownForRender(text), config = chatMarkdownConfig)
        }
    }
}

internal fun ViewContainer<*, *>.indexAnalyzingView(ctx: IndexDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 14f, left = 16f, bottom = 14f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        Text {
            attr {
                text("AI 正在分析中...")
                fontSize(14f)
                color(AppColor.TEXT_GRAY)
            }
        }

        Text {
            attr {
                text("请稍候，当前 AI 服务正在生成指数分析报告")
                fontSize(12f)
                color(AppColor.TEXT_HINT)
                marginTop(6f)
            }
        }

        // 与个股详情页同一套三点波浪（base/Anim.kt），两个页面的等待手感一致
        View { attr { marginTop(12f) } }
        aiDotWaveDots(ctx.aiDotWave, color = AppColor.SUCCESS)
    }
}

internal fun ViewContainer<*, *>.indexNotAnalyzedView(ctx: IndexDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 14f, left = 16f, bottom = 14f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        Text {
            attr {
                text("尚未进行 AI 分析")
                fontSize(14f)
                color(AppColor.TEXT_GRAY)
            }
        }

        View {
            attr {
                marginTop(12f)
                padding(top = 10f, left = 24f, bottom = 10f, right = 24f)
                backgroundColor(AppColor.SUCCESS)
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
                    color(AppColor.ON_DARK)
                }
            }
        }

        Text {
            attr {
                text("AI 将为您分析指数趋势、点位、风险并给出操作参考")
                fontSize(11f)
                color(AppColor.TEXT_HINT)
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
                color(StockColors.UP)
            }
        }

        Text {
            attr {
                text(ctx.loadErrorMessage.ifEmpty { "未找到指数 ${ctx.indexCode} 的数据" })
                fontSize(13f)
                color(AppColor.TEXT_HINT)
                marginTop(8f)
                textAlignCenter()
            }
        }

        View {
            attr {
                marginTop(16f)
                padding(top = 10f, left = 24f, bottom = 10f, right = 24f)
                backgroundColor(AppColor.SUCCESS)
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
                    color(AppColor.ON_DARK)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexAiToast(ctx: IndexDetailPage) {
    View {
        attr {
            absolutePosition(top = 70f, left = 0f, right = 0f)
            alignItems(FlexAlign.CENTER)
        }
        event { click { ctx.aiToast = "" } }
        View {
            attr {
                maxWidth(ctx.pagerData.pageViewWidth - 60f)
                backgroundColor(0xE62E7D32)
                borderRadius(10f)
                padding(left = 14f, top = 8f, right = 14f, bottom = 8f)
            }
            Text {
                attr {
                    text(ctx.aiToast)
                    fontSize(12f)
                    color(AppColor.ON_DARK)
                    textAlignCenter()
                }
            }
        }
    }
}

/** 指数详情页正文（Scroller 的全部内容）。由 detailEpoch 驱动整段重建。 */
private fun ViewContainer<*, *>.indexDetailContent(ctx: IndexDetailPage) {
    analysisHistoryPanel(ctx.analysisState) {
        ctx.clearChartSelection()
        ctx.clearHighlight()
        ctx.aiExpandedKeys.clear()
    }
    indexInfoCard(ctx)
    indexRealtimeCard(ctx)
    View {
        event { layoutFrameDidChange { frame -> ctx.chartAnchorY = frame.y } }
        indexKlineChartArea(ctx)
    }
    indexAiAnalysisCards(ctx)
    vif({ ctx.dataSourceText.isNotEmpty() }) {
        indexDataSourceFooter(ctx)
    }
}
