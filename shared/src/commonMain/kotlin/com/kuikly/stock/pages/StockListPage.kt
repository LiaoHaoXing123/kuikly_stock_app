// 股票列表页：支持搜索、排序与分页加载。

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

    /** 按压态：列表项按下的高亮由它驱动，页面唯一一份（单指只可能按一个）。 */
    internal val press = PressState(this)

    /** 下拉刷新触发时不弹 hint 气泡——进度由刷新头自己表达，避免双重反馈。 */
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
        // 首屏 loading 在 didInit 里就发起了（那时 body 还没构建，扫光无从谈起）。
        // 页面真正上屏时补一次：若骨架屏还在，扫光就从这个帧开始转。
        skeletonPulse.bump()
    }

    internal fun refreshData() {
        startRefresh(silent = false)
    }

    /** 下拉刷新入口：不弹 hint 气泡，进度由刷新头表达。 */
    internal fun refreshByPull() {
        startRefresh(silent = true)
    }

    private fun startRefresh(silent: Boolean) {
        if (isLoading) {
            // 已有请求在跑：立刻收掉刷新头，否则它会一直转
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

    /** 触底自动加载入口（替代原「加载更多」按钮）。 */
    internal fun loadMoreFromFooter() {
        if (isLoading) return
        if (!hasMore) {
            footerRefreshRef?.view?.endRefresh(FooterRefreshEndState.NONE_MORE_DATA)
            return
        }
        // 上次失败时不重复推进页码，原地重试同一页
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
            // 重新拉取时清掉尾部的「没有更多数据」，否则自动加载不会再触发
            footerRefreshRef?.view?.resetRefreshState(FooterRefreshState.IDLE)
        }
        // 骨架屏此刻才真正挂载：刷新的第一件事是清空列表，清空之后骨架才出现。
        // 时机错了（例如放在 isLoading=true 紧后面）扫光就赶不上首帧。
        skeletonPulse.bump()
        // 刷新头箭头（下拉与点「刷新」都会走到这里）
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

    /** 收掉下拉刷新头与触底加载态，避免指示器一直转。 */
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
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.PRIMARY_SOFT)
            paddingTop(ctx.pagerData.statusBarHeight)
            height(48f + ctx.pagerData.statusBarHeight)
        }

        View {
            attr { padding(12f, 16f, 12f, 16f) }
            event {
                click { ctx.backToDefaultList() }
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
                text(if (ctx.listMode == "指数") "指数行情" else "股票行情")
                fontSize(17f)
                fontWeightBold()
                color(AppColor.ON_DARK)
                marginLeft(8f)
            }
        }

        vif({ ctx.totalCount > 0 }) {
            Text {
                attr {
                    text("共 ${ctx.totalCount} " + if (ctx.listMode == "指数") "只" else "只")
                    fontSize(12f)
                    color(AppColor.ON_DARK_ACCENT_STRONG)
                    marginLeft(6f)
                }
            }
        }
        vif({ ctx.totalCount <= 0 }) {
            Text {
                attr {
                    text("共 ${ctx.stockList.size} " + if (ctx.listMode == "指数") "只" else "只")
                    fontSize(12f)
                    color(AppColor.ON_DARK_ACCENT_STRONG)
                    marginLeft(6f)
                }
            }
        }

        View { attr { flex(1f) } }

        View {
            attr { padding(10f, 12f, 8f, 12f) }
            event {
                click {
                    ctx.openModule(AppRoutes.WATCHLIST)
                }
            }
            Text {
                attr {
                    text("☆ 自选")
                    fontSize(13f)
                    color(AppColor.ON_DARK)
                }
            }
        }

        refreshButton({ ctx.isLoading }, foreground = AppColor.ON_DARK) { ctx.refreshData() }
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

/** 模式 Tab 的单项宽度（dp）。滑块宽度与它一致，百分比位移才能正好跨一格。 */
private const val MODE_TAB_W = 68f

/** 股票 / 指数两个模式，下标即滑块位移量。 */
private val MODE_TABS = listOf("股票", "指数")

/**
 * 股票 / 指数切换。
 *
 * 两个 chip 的选中态是底色硬切，切换时视觉上是「灭一盏、亮一盏」；滑块则是一个物体
 * 从左挪到右，用户能直接看出「是在两组数据之间切换」。结构与取色见 [segmentedControl]，
 * 这里的差异只在配色：滑块是白底、选中文字是主色，与 K 线周期那种「主色滑块 + 白字」相反。
 */
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
            thumbColor = AppColor.SURFACE,
            selectedTextColor = AppColor.PRIMARY_SOFT,
            fontSize = 13f,
            accessibilityLabel = { label, selected -> if (selected) "$label 行情，已选择" else label },
            onSelect = { ctx.switchMode(MODE_TABS[it]) },
        )

        View { attr { flex(1f) } }
    }
}

/** 排序单格宽度（dp）。滑块宽度与它一致，percentageX 位移才能正好跨一格。 */
private const val SORT_TAB_W = 54f

/** 排序项固定顺序，下标即滑块位移量。「成交量」3 个字，宽度按它取。 */
private val SORT_OPTIONS = listOf("默认", "涨幅", "跌幅", "成交量")

/**
 * 排序切换。
 *
 * 四个 chip 各自硬切底色时，切换像「灭一盏、亮一盏」，看不出选中的跑哪去了。
 * 换成和 K 线周期、股票/指数同一套「滑块 + 位移」：一个蓝色滑块在四格间平移，
 * 位移本身就表达「在同一组选项里切换」。结构见 [segmentedControl]。
 */
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
        // 触底自动加载：替代原来的「加载更多」按钮
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
            // 按下先给视觉反馈，松手才真的跳转。原来这里什么都没有，
            // 用户只能靠「松手后页面跳了」反推自己点到了——这就是原型感。
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

/**
 * 列表首屏骨架：列宽与内边距跟真实行严格一致，先把版式占住。
 * 数据到达时只是「填充」，页面不跳、眼睛不用重新找焦点——
 * 这比一行居中的「加载中...」信息量大得多。
 */
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

                // 名称（flex）
                View {
                    attr { flex(1f) }
                    skeletonBlock(height = 15f, w = 92f, sweep = sweep)
                }
                // 代码（60）
                View {
                    attr { width(60f) }
                    skeletonBlock(height = 12f, w = 44f, sweep = sweep)
                }
                // 最新价（68，右对齐）
                View {
                    attr { width(68f); alignItems(FlexAlign.FLEX_END) }
                    skeletonBlock(height = 16f, w = 50f, sweep = sweep)
                }
                // 涨跌额（56，右对齐）
                View {
                    attr { width(56f); alignItems(FlexAlign.FLEX_END) }
                    skeletonBlock(height = 12f, w = 42f, sweep = sweep)
                }
                // 涨跌幅（64，右对齐）
                View {
                    attr { width(64f); alignItems(FlexAlign.FLEX_END) }
                    skeletonBlock(height = 12f, w = 46f, sweep = sweep)
                }
            }
        }
    }
}

/** 首屏骨架行数：够铺满一屏即可，多画只是浪费。 */
private const val SKELETON_ROW_COUNT = 10

/**
 * 空态。
 *
 * 原来只有一行「暂无数据，请尝试其他搜索条件或刷新重试」——把用户丢在一个死胡同里：
 * 知道该做什么，但没有任何可点的东西。现在按当前筛选状态给出对应的**出口按钮**，
 * 让空态也是流程的一部分而不是终点。
 */
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

/**
 * 列表错误态。
 *
 * 原来直接把 loadErrorMessage（可能是 "HTTP 500: ..." 或异常 message）当正文摊出来，
 * 标红加粗像报错弹窗——那是给开发者看的。现在分两层：一行给人看的结论，
 * 原始信息降级成灰色小字（可自查、可截图反馈），主按钮仍是重试。
 */
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
