// 股票列表页：支持搜索、排序与分页加载。

package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
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
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch

@Page("stock_list")
class StockListPage : Pager() {

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

    internal var totalCount by observable(0)

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

                searchBar(ctx)

                sortBar(ctx)

                listHeaderRow()

                stockListView(ctx)

                vif({ !ctx.isLoading && !ctx.loadError && ctx.stockList.isNotEmpty() && ctx.hasMore }) {
                    loadMoreButton(ctx)
                }
                vif({ !ctx.isLoading && !ctx.loadError && ctx.stockList.isNotEmpty() && !ctx.hasMore }) {
                    noMoreButton()
                }

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

    internal fun refreshData() {
        if (isLoading) return
        showHint("正在刷新…")
        currentPage = 1
        currentKeyword = searchKeyword
        loadStockList(isRefresh = true)
    }

    internal fun searchStocks() {
        if (isLoading) return
        showHint("正在搜索…")
        currentKeyword = searchKeyword
        currentPage = 1
        loadStockList(isRefresh = true)
    }

    internal fun loadMore() {
        if (isLoading || loadError) return
        showHint("正在加载更多…")
        currentPage++
        loadStockList(isRefresh = false)
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
        hint = msg
        lifecycleScope.launch {
            delay(1200)
            if (hint == msg) hint = ""
        }
    }

    private fun loadStockList(isRefresh: Boolean) {
        if (isLoading) return
        isLoading = true
        loadError = false
        if (isRefresh) {
            stockList.clear()
            hasMore = true
        }

        lifecycleScope.launch {
            try {
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
            } catch (e: Throwable) {
                delay(0)
                loadError = true
                loadErrorMessage = e.message ?: "数据加载失败"
                showHint("加载失败：" + (e.message ?: "未知错误"))
            } finally {
                isLoading = false
            }
        }
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
                text("股票行情")
                fontSize(17f)
                fontWeightBold()
                color(0xFFFFFFFF)
                marginLeft(8f)
            }
        }

        vif({ ctx.totalCount > 0 }) {
            Text {
                attr {
                    text("共 ${ctx.totalCount} 只")
                    fontSize(12f)
                    color(0xFFB3D9FF)
                    marginLeft(6f)
                }
            }
        }
        vif({ ctx.totalCount <= 0 }) {
            Text {
                attr {
                    text("共 ${ctx.stockList.size} 只")
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
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                        .openPage("watchlist", JSONObject())
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

        View {
            attr { padding(10f, 12f, 12f, 12f) }
            event { click { ctx.refreshData() } }
            Text {
                attr {
                    text("刷新")
                    fontSize(13f)
                    color(0xFFFFFFFF)
                }
            }
        }
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
                    autofocus(true)
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
                        text("搜索股票代码或名称...")
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

internal fun ViewContainer<*, *>.sortBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(0xFFFFFFFF)
        }

        listOf("默认", "涨幅", "跌幅", "成交量").forEach { option ->
            vif({ ctx.sortOption == option }) {
                sortChipView(ctx, option, selected = true)
            }
            vif({ ctx.sortOption != option }) {
                sortChipView(ctx, option, selected = false)
            }
        }

        View { attr { flex(1f) } }

        legendItem(0xFFE53935, "涨")
        legendItem(0xFF43A047, "跌")
        legendItem(0xFF999999, "平")
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

internal fun ViewContainer<*, *>.sortChipView(ctx: StockListPage, option: String, selected: Boolean) {
    View {
        attr {
            marginRight(8f)
            padding(left = 10f, top = 4f, right = 10f, bottom = 4f)
            backgroundColor(if (selected) 0xFF1976D2 else 0xFFE3F2FD)
            borderRadius(12f)
        }
        event {
            click { ctx.applySort(option) }
        }
        Text {
            attr {
                text(option)
                fontSize(12f)
                fontWeightBold()
                color(if (selected) 0xFFFFFFFF else 0xFF1976D2)
            }
        }
    }
}

internal fun ViewContainer<*, *>.stockListView(ctx: StockListPage) {
    Scroller {
        attr {
            flex(1f)
            flexDirectionColumn()
            scrollEnable(true)
        }
        vif({ ctx.isLoading && ctx.stockList.isEmpty() }) {
            stockListLoadingView()
        }
        velseif({ ctx.loadError && ctx.stockList.isEmpty() }) {
            loadErrorView(ctx)
        }
        velseif({ ctx.stockList.isEmpty() }) {
            emptyView()
        }
        velse {
            vfor({ ctx.stockList }) { stock ->
                stockListItem(ctx, stock)
            }
            vif({ ctx.isLoading }) {
                loadingMoreView()
            }
        }
    }
}

internal fun ViewContainer<*, *>.stockListItem(
    ctx: StockListPage,
    stock: StockListItem
) {
    val pct = stock.changePercent
    val changeColor = when {
        pct == null || pct == 0.0 -> 0xFF999999
        pct > 0 -> 0xFFE53935
        else -> 0xFF43A047
    }
    val priceText = stock.price?.let { String.format("%.2f", it) } ?: "-"
    val changeText = stock.change?.let { String.format("%+.2f", it) } ?: "-"
    val pctText = stock.changePercent?.let { String.format("%+.2f%%", it) } ?: "-"

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(1f)
            padding(left = 16f, top = 12f, right = 16f, bottom = 12f)
            backgroundColor(0xFFFFFFFF)
        }
        event {
            click {
                val params = JSONObject()
                params.put("code", stock.code)
                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                    .openPage("stock_detail", params)
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
                        fontWeightBold()
                        color(if (hit) 0xFFE53935 else 0xFF333333)
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
                        color(if (hit) 0xFFE53935 else 0xFF888888)
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

internal fun ViewContainer<*, *>.stockListLoadingView() {
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
                fontSize(14f)
                color(0xFF666666)
            }
        }
    }
}

internal fun ViewContainer<*, *>.emptyView() {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        Text {
            attr {
                text("暂无数据\n\n请尝试其他搜索条件或刷新重试")
                fontSize(14f)
                color(0xFF999999)
                textAlignCenter()
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
        }
        Text {
            attr {
                text("加载失败\n\n${ctx.loadErrorMessage}")
                fontSize(14f)
                color(0xFFE53935)
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

internal fun ViewContainer<*, *>.loadingMoreView() {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 12f, top = 0f, right = 12f, bottom = 0f)
        }
        Text {
            attr {
                text("加载更多...")
                fontSize(13f)
                color(0xFF999999)
            }
        }
    }
}

internal fun ViewContainer<*, *>.loadMoreButton(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 12f, top = 12f, right = 12f, bottom = 12f)
            backgroundColor(0xFFFFFFFF)
        }
        event {
            click { ctx.loadMore() }
        }
        Text {
            attr {
                text("加载更多")
                fontSize(13f)
                color(0xFF1976D2)
            }
        }
    }
}

internal fun ViewContainer<*, *>.noMoreButton() {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 12f, top = 12f, right = 12f, bottom = 12f)
            backgroundColor(0xFFFFFFFF)
        }
        Text {
            attr {
                text("没有更多数据")
                fontSize(13f)
                color(0xFFBBBBBB)
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
    val volume: Double? = null
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
