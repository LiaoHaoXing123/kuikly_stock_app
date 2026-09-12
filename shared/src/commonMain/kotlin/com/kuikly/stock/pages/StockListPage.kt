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
import com.kuikly.stock.base.PressState
import com.kuikly.stock.base.pressedScale
import com.kuikly.stock.base.hapticTick
import com.kuikly.stock.base.pressFeedback
import com.kuikly.stock.base.pressedBg
import com.kuikly.stock.base.skeletonBlock

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
                    backgroundColor(0xFFF5F5F5)
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
            backgroundColor(0xFF1976D2)
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
                    color(0xFFFFFFFF)
                }
            }
        }

        Text {
            attr {
                text(if (ctx.listMode == "指数") "指数行情" else "股票行情")
                fontSize(17f)
                fontWeightBold()
                color(0xFFFFFFFF)
                marginLeft(8f)
            }
        }

        vif({ ctx.totalCount > 0 }) {
            Text {
                attr {
                    text("共 ${ctx.totalCount} " + if (ctx.listMode == "指数") "只" else "只")
                    fontSize(12f)
                    color(0xFFB3D9FF)
                    marginLeft(6f)
                }
            }
        }
        vif({ ctx.totalCount <= 0 }) {
            Text {
                attr {
                    text("共 ${ctx.stockList.size} " + if (ctx.listMode == "指数") "只" else "只")
                    fontSize(12f)
                    color(0xFFB3D9FF)
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
                    color(0xFFFFFFFF)
                }
            }
        }

        refreshButton({ ctx.isLoading }, foreground = 0xFFFFFFFF) { ctx.refreshData() }
    }
}

internal fun ViewContainer<*, *>.searchBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(0xFFFFFFFF)
        }

        View {
            attr {
                flex(1f)
                height(36f)
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                backgroundColor(0xFFF5F5F5)
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
                    color(Color(0xFF333333))
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
                        color(0xFF999999)
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
                backgroundColor(0xFF1976D2)
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
                    color(0xFFFFFFFF)
                    fontWeightBold()
                }
            }
        }
    }
}

/** 模式 Tab 的单项宽度（dp）。指示器宽度与它一致，百分比位移才能正好跨一格。 */
private const val MODE_TAB_W = 68f

private val MODE_TAB_ANIMATION = Animation.easeOut(0.2f)

/**
 * 股票 / 指数切换。
 *
 * 用「白色滑块 + 位移动画」取代原来的两个 chip：chip 的选中态是底色硬切，
 * 切标签时视觉上是「灭一盏、亮一盏」；滑块则是一个物体从左边挪到右边，
 * 用户能直接看出「当前是在两组数据之间切换」，而不是两个独立按钮。
 *
 * 位移用**百分比**而不是 dp：`Translate` 的 offsetX 会走 frame 任务、拿不到动画窗口
 * （序列化时还会被丢掉），而 percentageX 是相对元素自身宽度的——滑块宽 = 单格宽，
 * 所以 percentageX=1 正好跨一格，与容器实际宽度无关。
 */
internal fun ViewContainer<*, *>.modeTabBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(0xFFFFFFFF)
        }

        View {
            attr {
                flexDirectionRow()
                backgroundColor(0xFFEFF3F8)
                borderRadius(16f)
                padding(3f)
            }

            // 滑块：绝对定位铺满内区高度，靠 transform 平移
            View {
                attr {
                    // 先读 observable 再声明动画（顺序不能反，见 Interaction.kt）
                    val mode = ctx.listMode
                    animate(MODE_TAB_ANIMATION, mode)
                    absolutePosition(top = 3f, left = 3f, bottom = 3f)
                    width(MODE_TAB_W)
                    borderRadius(13f)
                    backgroundColor(0xFFFFFFFF)
                    transform(translate = Translate(percentageX = if (mode == "指数") 1f else 0f))
                }
            }

            modeTab(ctx, "股票", "股票行情，已选择")
            modeTab(ctx, "指数", "指数行情，已选择")
        }

        View { attr { flex(1f) } }
    }
}

private fun ViewContainer<*, *>.modeTab(ctx: StockListPage, mode: String, selectedLabel: String) {
    View {
        attr {
            width(MODE_TAB_W)
            height(30f)
            allCenter()
            accessibility(if (ctx.listMode == mode) selectedLabel else mode)
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(ctx.listMode != mode, false)
        }
        event { click { hapticTick(HapticStyle.Light); ctx.switchMode(mode) } }
        Text {
            attr {
                text(mode)
                fontSize(13f)
                if (ctx.listMode == mode) fontWeightBold()
                color(if (ctx.listMode == mode) 0xFF1976D2 else 0xFF7A8797)
            }
        }
    }
}

/** 排序单格宽度（dp）。指示器宽度与它一致，percentageX 位移才能正好跨一格。 */
private const val SORT_TAB_W = 54f

/** 排序项固定顺序，下标即滑块位移量。「成交量」3 个字，宽度按它取。 */
private val SORT_OPTIONS = listOf("默认", "涨幅", "跌幅", "成交量")

private val SORT_TAB_ANIMATION = Animation.easeOut(0.2f)

/**
 * 排序切换。
 *
 * 原来是四个 chip 各自硬切底色，切换时像「灭一盏、亮一盏」，看不出选中的跑哪去了。
 * 换成和 K 线周期（日K/周K/月K）、股票/指数同一套「滑块 + 位移」：一个蓝色滑块在四格间
 * 平移，位移本身就表达「在同一组选项里切换」。位移用百分比（相对滑块自身宽度=单格宽），
 * 原因见 modeTabBar 注释。
 */
internal fun ViewContainer<*, *>.sortBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(0xFFFFFFFF)
        }

        vif({ ctx.listMode != "指数" }) {
            View {
                attr {
                    flexDirectionRow()
                    backgroundColor(0xFFF0F4F9)
                    borderRadius(14f)
                    padding(3f)
                }

                // 滑块：绝对定位铺满内区高度，靠 transform 平移到选中格
                View {
                    attr {
                        // 先读 observable 再声明动画（顺序不能反，见 Interaction.kt）
                        val sort = ctx.sortOption
                        animate(SORT_TAB_ANIMATION, sort)
                        absolutePosition(top = 3f, left = 3f, bottom = 3f)
                        width(SORT_TAB_W)
                        borderRadius(11f)
                        backgroundColor(0xFF1976D2)
                        transform(translate = Translate(percentageX = sortOffset(sort)))
                    }
                }

                SORT_OPTIONS.forEach { option -> sortTab(ctx, option) }
            }
        }

        View { attr { flex(1f) } }

        legendItem(StockColors.UP, "涨")
        legendItem(StockColors.DOWN, "跌")
        legendItem(0xFF999999, "平")
    }
}

private fun ViewContainer<*, *>.sortTab(ctx: StockListPage, option: String) {
    View {
        attr {
            width(SORT_TAB_W)
            height(28f)
            allCenter()
            accessibility(if (ctx.sortOption == option) "$option，已选择" else option)
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(ctx.sortOption != option, false)
        }
        event { click { hapticTick(HapticStyle.Light); ctx.applySort(option) } }
        Text {
            attr {
                text(option)
                fontSize(12f)
                fontWeightBold()
                color(if (ctx.sortOption == option) 0xFFFFFFFF else 0xFF666666)
            }
        }
    }
}

private fun sortOffset(option: String): Float =
    SORT_OPTIONS.indexOf(option).coerceAtLeast(0).toFloat()

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
                color(0xFF888888)
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
            backgroundColor(0xFFF7F8FA)
        }

        Text { attr { text("名称"); fontSize(11f); color(0xFF999999); flex(1f) } }
        Text { attr { text("代码"); fontSize(11f); color(0xFF999999); width(60f) } }
        Text { attr { text("最新价"); fontSize(11f); color(0xFF999999); width(68f); textAlignRight() } }
        Text { attr { text("涨跌额"); fontSize(11f); color(0xFF999999); width(56f); textAlignRight() } }
        Text { attr { text("涨跌幅"); fontSize(11f); color(0xFF999999); width(64f); textAlignRight() } }
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
        pct == null || pct == 0.0 -> 0xFF999999
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
            pressedBg(ctx.press, rowTag, normal = 0xFFFFFFFF)
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
                        color(if (hit) StockColors.UP else 0xFF333333)
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
                        color(if (hit) StockColors.UP else 0xFF888888)
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
                    backgroundColor(0xFFFFFFFF)
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
                color(0xFF333333)
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
                color(0xFF999999)
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
            backgroundColor(if (primary) 0xFF1976D2 else 0xFFE8F2FF)
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
                color(if (primary) 0xFFFFFFFF else 0xFF1976D2)
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
                color(0xFF333333)
                textAlignCenter()
            }
        }
        Text {
            attr {
                text("网络或数据源可能暂时不可用，重试通常就能恢复。")
                fontSize(13f)
                color(0xFF888888)
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
                    color(0xFFAAAAAA)
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
                backgroundColor(0xFF1976D2)
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
                    color(0xFFFFFFFF)
                }
            }
        }
    }
}

private const val LIST_RETRY_TAG = "stock_list_error_retry"

internal fun ViewContainer<*, *>.hintPopup(ctx: StockListPage) {
    View {
        attr {
            absolutePosition(top = 96f, left = 0f, right = 0f)
            alignItems(FlexAlign.CENTER)
        }
        event { click { ctx.hint = "" } }
        View {
            attr {
                maxWidth(ctx.pagerData.pageViewWidth - 80f)
                backgroundColor(0xE6333333)
                borderRadius(20f)
                padding(left = 18f, top = 9f, right = 18f, bottom = 9f)
            }
            Text {
                attr {
                    text(ctx.hint)
                    fontSize(13f)
                    color(0xFFFFFFFF)
                    textAlignCenter()
                    lineHeight(1.5f)
                }
            }
        }
    }
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
