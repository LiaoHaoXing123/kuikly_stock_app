package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.data.StockColors

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.data.StockDb
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSigned2
import com.kuikly.stock.data.fmtSignedPct
import com.kuikly.stock.base.HapticStyle
import com.kuikly.stock.ui.component.topToast
import com.kuikly.stock.ui.component.PressState
import com.kuikly.stock.ui.component.pressedScale
import com.kuikly.stock.base.hapticTick
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedBg
import com.kuikly.stock.ui.component.skeletonBlock
import com.kuikly.stock.ui.component.segmentedControl
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.REFRESH_SPIN_STEP_MS
import com.kuikly.stock.ui.component.appBottomNav
import com.kuikly.stock.ui.component.autoLoadFooter
import com.kuikly.stock.ui.component.loadMoreLabel
import com.kuikly.stock.ui.component.pullRefreshLabel
import com.kuikly.stock.ui.component.pullToRefresh
import com.kuikly.stock.ui.component.refreshButton
import com.kuikly.stock.ui.component.openModule
import com.kuikly.stock.ui.theme.AppColor

@Page("stock_list")
class StockListPage : BasePager() {

    internal var stockList: ObservableList<StockListItem> by observableList()

    internal var searchKeyword by observable("")

    internal var currentPage by observable(1)

    internal var isLoading by observable(false)

    internal var loadError by observable(false)

    internal var loadErrorMessage by observable("")

    internal var currentKeyword by observable("")

    internal var hint by observable("")

    internal var hasMore by observable(true)

    internal var sortOption by observable("默认")

    internal var listMode by observable("股票")

    internal var totalCount by observable(0)

    internal var pullState by observable(RefreshViewState.IDLE)

    internal var footerState by observable(FooterRefreshState.IDLE)

    internal var pullRefreshRef: ViewRef<RefreshView>? = null

    internal var footerRefreshRef: ViewRef<FooterRefreshView>? = null

    internal val press = PressState(this)

    private var silentLoad = false

    internal var searchInput: com.tencent.kuikly.core.views.InputView? = null

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(AppColor.SURFACE_SOFT)
                }

                navigationBar(ctx)

                modeTabBar(ctx)

                searchBar(ctx)

                sortBar(ctx)

                listHeaderRow()

                stockListView(ctx)

                appBottomNav(ctx, AppRoutes.MARKET)

                vif({ ctx.hint.isNotEmpty() }) {
                    hintPopup(ctx)
                }
            }
        }
    }

    override fun didInit() {
        super.didInit()
        refreshData()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()

        skeletonPulse.bump()
    }

    internal fun refreshData() {
        startRefresh(silent = false)
    }

    internal fun refreshByPull() {
        startRefresh(silent = true)
    }

    private fun startRefresh(silent: Boolean) {
        if (isLoading) {

            pullRefreshRef?.view?.endRefresh()
            return
        }
        if (!silent) showHint("正在刷新…")
        currentPage = 1
        currentKeyword = searchKeyword
        loadStockList(isRefresh = true, silent = silent)
    }

    internal fun searchStocks() {
        if (isLoading) return
        showHint("正在搜索…")
        currentKeyword = searchKeyword
        currentPage = 1
        loadStockList(isRefresh = true)
    }

    internal fun loadMoreFromFooter() {
        if (isLoading) return
        if (!hasMore) {
            footerRefreshRef?.view?.endRefresh(FooterRefreshEndState.NONE_MORE_DATA)
            return
        }

        if (!loadError) currentPage++
        loadStockList(isRefresh = false, silent = true)
    }

    internal fun applySort(option: String) {
        if (isLoading) return
        if (sortOption == option) return
        sortOption = option
        showHint("正在按「$option」排序…")
        currentPage = 1
        currentKeyword = searchKeyword
        loadStockList(isRefresh = true)
    }

    internal fun switchMode(mode: String) {
        if (isLoading || listMode == mode) return
        listMode = mode
        currentPage = 1
        currentKeyword = searchKeyword
        sortOption = "默认"
        showHint(if (mode == "指数") "切换到指数行情" else "切换到股票行情")
        loadStockList(isRefresh = true)
    }

    private fun sortParams(): Pair<String?, String?> {
        return when (sortOption) {
            "涨幅" -> "change_percent" to "desc"
            "跌幅" -> "change_percent" to "asc"
            "成交量" -> "volume" to "desc"
            else -> null to null
        }
    }

    internal fun backToDefaultList() {
        val filtered = searchKeyword.isNotEmpty() || sortOption != "默认"
        if (filtered) {
            searchKeyword = ""
            currentKeyword = ""
            searchInput?.setText("")
            sortOption = "默认"
            currentPage = 1
            showHint("已返回全部行情")
            loadStockList(isRefresh = true)
        } else {
            acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
        }
    }

    internal fun showHint(msg: String) {
        if (silentLoad) return
        hint = msg
        lifecycleScope.launch {
            delay(1200)
            if (hint == msg) hint = ""
        }
    }

    private fun loadStockList(isRefresh: Boolean, silent: Boolean = false) {
        if (isLoading) return
        isLoading = true
        loadError = false
        silentLoad = silent
        if (isRefresh) {
            stockList.clear()
            hasMore = true

            footerRefreshRef?.view?.resetRefreshState(FooterRefreshState.IDLE)
        }

        skeletonPulse.bump()

        refreshSpin.loop(REFRESH_SPIN_STEP_MS) { isLoading }

        lifecycleScope.launch {
            try {
                if (listMode == "指数") {
                    val indices = StockDb.listIndices(currentKeyword.ifBlank { null })
                    delay(0)
                    totalCount = indices.size
                    stockList.addAll(indices)
                    hasMore = false
                    showHint(if (indices.isNotEmpty()) "共 ${indices.size} 只指数" else "未找到相关指数")
                } else {
                    val (sortCol, sortDir) = sortParams()
                    val (total, localList) = StockRepository.loadStockListWithTotal(
                        currentKeyword.ifBlank { null }, sortCol, sortDir
                    )

                    val startIdx = if (isRefresh) 0 else stockList.size
                    val pageItems = localList.drop(startIdx).take(20)

                    delay(0)
                    totalCount = total
                    if (pageItems.isNotEmpty()) {
                        stockList.addAll(pageItems)
                        hasMore = pageItems.size >= 20
                        showHint(if (isRefresh) "已刷新，共 ${stockList.size} 只股票" else "已加载 ${pageItems.size} 条")
                    } else if (stockList.isEmpty()) {
                        hasMore = false
                        showHint("未找到相关股票")
                    } else {
                        hasMore = false
                        showHint("没有更多了")
                    }
                }
            } catch (e: Throwable) {
                delay(0)
                loadError = true
                loadErrorMessage = e.message ?: "数据加载失败"
                showHint("加载失败：" + (e.message ?: "未知错误"))
            } finally {
                isLoading = false
                silentLoad = false
                finishRefreshIndicators()
            }
        }
    }

    private fun finishRefreshIndicators() {
        pullRefreshRef?.view?.endRefresh()
        footerRefreshRef?.view?.endRefresh(
            if (hasMore) FooterRefreshEndState.SUCCESS else FooterRefreshEndState.NONE_MORE_DATA
        )
    }
}

internal fun ViewContainer<*, *>.navigationBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow(); alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.SURFACE)
            padding(top = ctx.pagerData.statusBarHeight, left = 18f, right = 10f)
            height(com.kuikly.stock.ui.theme.AppSize.TITLE_BAR + ctx.pagerData.statusBarHeight)
        }
        View {
            attr { flex(1f) }
            Text { attr { text(if (ctx.listMode == "指数") "指数行情" else "股票行情"); fontSize(18f); fontWeightBold(); color(AppColor.TITLE) } }
            Text { attr { text("本地行情 · 共 ${ctx.totalCount} 只"); fontSize(10f); color(AppColor.TEXT_SUB); marginTop(1f) } }
        }
        vif({ ctx.searchKeyword.isNotEmpty() || ctx.sortOption != "默认" }) {
            View {
                attr { minWidth(52f); height(44f); allCenter(); accessibility("清除筛选") }
                event { click { ctx.backToDefaultList() } }
                Text { attr { text("清除筛选"); fontSize(11f); color(AppColor.PRIMARY) } }
            }
        }
        refreshButton({ ctx.isLoading }) { ctx.refreshData() }
    }
}

internal fun ViewContainer<*, *>.searchBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(AppColor.SURFACE)
        }

        View {
            attr {
                flex(1f)
                height(36f)
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                backgroundColor(AppColor.SURFACE_SOFT)
                borderRadius(18f)
                padding(left = 12f, right = 12f)
            }

            Input {
                ref {
                    ctx.searchInput = it.view
                }
                attr {
                    flex(1f)
                    height(36f)
                    fontSize(14f)
                    color(Color(AppColor.TEXT_INK))
                    editable(true)
                    returnKeyTypeSearch()
                    maxTextLength(20)
                }
                event {
                    inputFocus { }
                    textDidChange(isSyncEdit = true) { params ->
                        ctx.searchKeyword = params.text
                    }
                    inputReturn { params ->
                        ctx.searchKeyword = params.text
                        ctx.searchStocks()
                    }
                }
            }

            vif({ ctx.searchKeyword.isEmpty() }) {
                Text {
                    attr {
                        text(if (ctx.listMode == "指数") "搜索指数代码或名称..." else "搜索股票代码或名称...")
                        fontSize(14f)
                        color(AppColor.TEXT_HINT)
                        absolutePosition(left = 12f, top = 9f)
                    }
                    event {
                        click {
                            ctx.searchInput?.focus()
                        }
                    }
                }
            }
        }

        View {
            attr {
                width(60f)
                height(36f)
                backgroundColor(AppColor.PRIMARY_SOFT)
                borderRadius(18f)
                alignItems(FlexAlign.CENTER)
                justifyContent(FlexJustifyContent.CENTER)
                marginLeft(8f)
            }
            event {
                click { ctx.searchStocks() }
            }
            Text {
                attr {
                    text("搜索")
                    fontSize(14f)
                    color(AppColor.ON_DARK)
                    fontWeightBold()
                }
            }
        }
    }
}

private const val MODE_TAB_W = 68f

private val MODE_TABS = listOf("股票", "指数")

internal fun ViewContainer<*, *>.modeTabBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(AppColor.SURFACE)
        }

        segmentedControl(
            options = MODE_TABS,
            selectedIndex = { MODE_TABS.indexOf(ctx.listMode) },
            itemWidth = MODE_TAB_W,
            height = 30f,
            trackRadius = 16f,
            thumbRadius = 13f,
            thumbColor = { AppColor.SURFACE },
            selectedTextColor = { AppColor.PRIMARY_SOFT },
            fontSize = 13f,
            accessibilityLabel = { label, selected -> if (selected) "$label 行情，已选择" else label },
            onSelect = { ctx.switchMode(MODE_TABS[it]) },
        )

        View { attr { flex(1f) } }
    }
}

private const val SORT_TAB_W = 54f

private val SORT_OPTIONS = listOf("默认", "涨幅", "跌幅", "成交量")

internal fun ViewContainer<*, *>.sortBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(AppColor.SURFACE)
        }

        vif({ ctx.listMode != "指数" }) {
            segmentedControl(
                options = SORT_OPTIONS,
                selectedIndex = { SORT_OPTIONS.indexOf(ctx.sortOption) },
                itemWidth = SORT_TAB_W,
                onSelect = { ctx.applySort(SORT_OPTIONS[it]) },
            )
        }

        View { attr { flex(1f) } }

        legendItem(StockColors.UP, "涨")
        legendItem(StockColors.DOWN, "跌")
        legendItem(AppColor.TEXT_HINT, "平")
    }
}

internal fun ViewContainer<*, *>.legendItem(color: Long, label: String) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginLeft(8f)
        }
        View {
            attr {
                width(8f)
                height(8f)
                backgroundColor(color)
                borderRadius(4f)
            }
        }
        Text {
            attr {
                text(label)
                fontSize(10f)
                color(AppColor.TEXT_HINT_SOFT)
                marginLeft(3f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.listHeaderRow() {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 16f, top = 6f, right = 16f, bottom = 6f)
            backgroundColor(AppColor.SURFACE_ALT)
        }

        Text { attr { text("名称"); fontSize(11f); color(AppColor.TEXT_HINT); flex(1f) } }
        Text { attr { text("代码"); fontSize(11f); color(AppColor.TEXT_HINT); width(60f) } }
        Text { attr { text("最新价"); fontSize(11f); color(AppColor.TEXT_HINT); width(68f); textAlignRight() } }
        Text { attr { text("涨跌额"); fontSize(11f); color(AppColor.TEXT_HINT); width(56f); textAlignRight() } }
        Text { attr { text("涨跌幅"); fontSize(11f); color(AppColor.TEXT_HINT); width(64f); textAlignRight() } }
    }
}

internal fun ViewContainer<*, *>.stockListView(ctx: StockListPage) {
    Scroller {
        attr {
            flex(1f)
            flexDirectionColumn()
            scrollEnable(true)
        }
        pullToRefresh(
            bind = { ctx.pullRefreshRef = it },
            label = { pullRefreshLabel(ctx.pullState, ctx.isLoading) },
            onStateChange = { ctx.pullState = it },
            onRefresh = { ctx.refreshByPull() },
            spin = ctx.refreshSpin,
            spinning = { ctx.isLoading },
        )
        vif({ ctx.isLoading && ctx.stockList.isEmpty() }) {
            stockListLoadingView(ctx)
        }
        velseif({ ctx.loadError && ctx.stockList.isEmpty() }) {
            loadErrorView(ctx)
        }
        velseif({ ctx.stockList.isEmpty() }) {
            emptyView(ctx)
        }
        velse {
            vfor({ ctx.stockList }) { stock ->
                stockListItem(ctx, stock)
            }
        }

        autoLoadFooter(
            bind = { ctx.footerRefreshRef = it },
            label = { loadMoreLabel(ctx.footerState, ctx.hasMore, ctx.isLoading) },
            onStateChange = { ctx.footerState = it },
            onLoadMore = { ctx.loadMoreFromFooter() },
        )
    }
}

internal fun ViewContainer<*, *>.stockListItem(
    ctx: StockListPage,
    stock: StockListItem
) {
    val pct = stock.changePercent
    val changeColor = when {
        pct == null || pct == 0.0 -> AppColor.TEXT_HINT
        pct > 0 -> StockColors.UP
        else -> StockColors.DOWN
    }
    val priceText = stock.price?.let { fmt2(it) } ?: "-"
    val changeText = stock.change?.let { fmtSigned2(it) } ?: "-"
    val pctText = stock.changePercent?.let { fmtSignedPct(it) } ?: "-"

    val rowTag = "stock_row:${stock.code}"

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(1f)
            padding(left = 16f, top = 12f, right = 16f, bottom = 12f)
            pressedBg(ctx.press, rowTag, normal = AppColor.SURFACE)
        }
        event {

            pressFeedback(ctx.press, rowTag)
            click {
                hapticTick(HapticStyle.Light)
                ctx.press.releaseAll()
                val params = JSONObject()
                params.put("code", stock.code)
                val route = if (ctx.listMode == "指数") "index_detail" else "stock_detail"
                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                    .openPage(route, params)
            }
        }

        View {
            attr {
                flex(1f)
                flexDirectionRow()
            }
            highlightSegments(stock.name ?: "未知", ctx.currentKeyword).forEach { (seg, hit) ->
                Text {
                    attr {
                        text(seg)
                        fontSize(15f)
                        color(if (hit) StockColors.UP else AppColor.TEXT_INK)
                    }
                }
            }
        }

        View {
            attr {
                flexDirectionRow()
                width(60f)
            }
            highlightSegments(stock.code, ctx.currentKeyword).forEach { (seg, hit) ->
                Text {
                    attr {
                        text(seg)
                        fontSize(12f)
                        fontWeightBold()
                        color(if (hit) StockColors.UP else AppColor.TEXT_HINT_SOFT)
                    }
                }
            }
        }

        Text {
            attr {
                text(priceText)
                fontSize(16f)
                fontWeightBold()
                color(changeColor)
                width(68f)
                textAlignRight()
            }
        }

        Text {
            attr {
                text(changeText)
                fontSize(12f)
                color(changeColor)
                width(56f)
                textAlignRight()
            }
        }

        Text {
            attr {
                text(pctText)
                fontSize(12f)
                color(changeColor)
                width(64f)
                textAlignRight()
            }
        }
    }
}

internal fun ViewContainer<*, *>.stockListLoadingView(ctx: StockListPage) {
    val sweep = ctx.skeletonPulse
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
        }

        repeat(SKELETON_ROW_COUNT) {
            View {
                attr {
                    flexDirectionRow()
                    alignItems(FlexAlign.CENTER)
                    marginTop(1f)
                    padding(left = 16f, top = 12f, right = 16f, bottom = 12f)
                    backgroundColor(AppColor.SURFACE)
                }

                View {
                    attr { flex(1f) }
                    skeletonBlock(height = 15f, w = 92f, sweep = sweep)
                }

                View {
                    attr { width(60f) }
                    skeletonBlock(height = 12f, w = 44f, sweep = sweep)
                }

                View {
                    attr { width(68f); alignItems(FlexAlign.FLEX_END) }
                    skeletonBlock(height = 16f, w = 50f, sweep = sweep)
                }

                View {
                    attr { width(56f); alignItems(FlexAlign.FLEX_END) }
                    skeletonBlock(height = 12f, w = 42f, sweep = sweep)
                }

                View {
                    attr { width(64f); alignItems(FlexAlign.FLEX_END) }
                    skeletonBlock(height = 12f, w = 46f, sweep = sweep)
                }
            }
        }
    }
}

private const val SKELETON_ROW_COUNT = 10

internal fun ViewContainer<*, *>.emptyView(ctx: StockListPage) {
    val filtered = ctx.currentKeyword.isNotEmpty() || ctx.sortOption != "默认"
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 32f, right = 32f)
        }
        Text {
            attr {
                text(if (filtered) "没有匹配的${if (ctx.listMode == "指数") "指数" else "股票"}" else "暂无行情数据")
                fontSize(16f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
                textAlignCenter()
            }
        }
        Text {
            attr {
                text(
                    if (filtered) "当前筛选：「${ctx.currentKeyword.ifEmpty { "全部" }}」" +
                        (if (ctx.sortOption != "默认") " · 按${ctx.sortOption}排序" else "")
                    else "数据源可能还在更新，稍后刷新即可"
                )
                fontSize(13f)
                color(AppColor.TEXT_HINT)
                marginTop(8f)
                textAlignCenter()
                lineHeight(19f)
            }
        }

        View {
            attr { flexDirectionRow(); marginTop(18f) }
            if (filtered) {
                emptyStateButton("清空筛选条件", primary = true, tag = LIST_RESET_TAG, ctx = ctx) {
                    ctx.backToDefaultList()
                }
            }
            if (filtered) View { attr { width(12f) } }
            emptyStateButton("刷新行情", primary = !filtered, tag = LIST_REFRESH_TAG, ctx = ctx) {
                ctx.refreshData()
            }
        }
    }
}

private const val LIST_RESET_TAG = "stock_list_empty_reset"
private const val LIST_REFRESH_TAG = "stock_list_empty_refresh"

private fun ViewContainer<*, *>.emptyStateButton(
    label: String,
    primary: Boolean,
    tag: String,
    ctx: StockListPage,
    action: () -> Unit,
) {
    View {
        attr {
            padding(top = 11f, left = 22f, bottom = 11f, right = 22f)
            backgroundColor(if (primary) AppColor.PRIMARY_SOFT else AppColor.PRIMARY_BG_LIGHT)
            borderRadius(20f)
            pressedScale(ctx.press, tag, normal = 1f, pressed = 0.97f)
            accessibility(label)
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event {
            pressFeedback(ctx.press, tag)
            click {
                ctx.press.releaseAll()
                action()
            }
        }
        Text {
            attr {
                text(label)
                fontSize(13f)
                fontWeightBold()
                color(if (primary) AppColor.ON_DARK else AppColor.PRIMARY_SOFT)
            }
        }
    }
}

internal fun ViewContainer<*, *>.loadErrorView(ctx: StockListPage) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 32f, right = 32f)
        }
        Text {
            attr {
                text("行情加载失败")
                fontSize(16f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
                textAlignCenter()
            }
        }
        Text {
            attr {
                text("网络或数据源可能暂时不可用，重试通常就能恢复。")
                fontSize(13f)
                color(AppColor.TEXT_HINT_SOFT)
                marginTop(8f)
                textAlignCenter()
                lineHeight(19f)
            }
        }
        if (ctx.loadErrorMessage.isNotEmpty()) {
            Text {
                attr {
                    text(ctx.loadErrorMessage)
                    fontSize(11f)
                    color(AppColor.TEXT_MUTED)
                    marginTop(10f)
                    textAlignCenter()
                    lines(3)
                }
            }
        }
        View {
            attr {
                marginTop(18f)
                padding(top = 11f, left = 28f, bottom = 11f, right = 28f)
                backgroundColor(AppColor.PRIMARY_SOFT)
                borderRadius(22f)
                pressedScale(ctx.press, LIST_RETRY_TAG, normal = 1f, pressed = 0.97f)
                accessibility("重试加载行情")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event {
                pressFeedback(ctx.press, LIST_RETRY_TAG)
                click {
                    ctx.press.releaseAll()
                    ctx.refreshData()
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

private const val LIST_RETRY_TAG = "stock_list_error_retry"

internal fun ViewContainer<*, *>.hintPopup(ctx: StockListPage) {
    topToast(
        ctx = ctx,
        text = { ctx.hint },
        onDismiss = { ctx.hint = "" },
        tint = AppColor.TEXT_INK,
        top = 96f,
        maxWidthInset = 80f,
        radius = 20f,
        hPadding = 18f,
        vPadding = 9f,
        fontSize = 13f,
        lineHeight = 1.5f,
    )
}

data class StockListItem(
    val code: String,
    val name: String? = null,
    val price: Double? = null,
    val changePercent: Double? = null,
    val change: Double? = null,
    val volume: Double? = null,
    val isIndex: Boolean = false
)

internal fun highlightSegments(text: String, keyword: String): List<Pair<String, Boolean>> {
    if (keyword.isBlank()) return listOf(text to false)
    val k = keyword.trim().uppercase()
    if (k.isEmpty()) return listOf(text to false)
    val t = text.uppercase()
    val segs = mutableListOf<Pair<String, Boolean>>()
    var i = 0
    while (i < text.length) {
        val idx = t.indexOf(k, i)
        if (idx < 0) {
            segs.add(text.substring(i) to false)
            break
        }
        if (idx > i) segs.add(text.substring(i, idx) to false)
        segs.add(text.substring(idx, idx + k.length) to true)
        i = idx + k.length
    }
    return segs
}
