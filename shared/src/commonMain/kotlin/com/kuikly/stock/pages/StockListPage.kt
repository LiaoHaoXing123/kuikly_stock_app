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

/**
 * 行情列表页（Task 1 首页）
 *
 * 功能：
 * 1. 展示股票名称、代码、最新价、涨跌幅
 * 2. 支持搜索（按代码或名称）
 * 3. 支持排序（按涨跌幅/成交量/市值）
 * 4. 支持分页加载
 * 5. 点击跳转到个股详情页
 */
@Page("stock_list")
class StockListPage : Pager() {

    // 状态：股票列表数据（ObservableList + vfor 实现响应式刷新）
    internal var stockList: ObservableList<StockListItem> by observableList()

    // 状态：搜索关键词
    internal var searchKeyword by observable("")

    // 状态：当前页码
    internal var currentPage by observable(1)

    // 状态：是否正在加载
    internal var isLoading by observable(false)

    // 状态：是否加载失败
    internal var loadError by observable(false)

    // 状态：加载失败的具体原因（用于界面展示，方便定位网络/序列化问题）
    internal var loadErrorMessage by observable("")

    // 存储当前关键词，供加载更多复用
    internal var currentKeyword by observable("")

    // 状态：操作提示（刷新/加载更多后的反馈，2 秒后自动消失）
    internal var hint by observable("")

    // 状态：是否还有更多数据（控制"加载更多"按钮显隐）
    internal var hasMore by observable(true)

    // 状态：当前排序方式（默认 / 涨幅 / 跌幅 / 成交量）
    internal var sortOption by observable("默认")

    // 状态：数据总条数（标题"共 N 只"显示；加载完成后更新为匹配总数）
    internal var totalCount by observable(0)

    // 搜索输入框实例引用（供占位文字点击聚焦）
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

                // 顶部导航栏
                navigationBar(ctx)

                // 搜索栏
                searchBar(ctx)

                // 排序标签栏
                sortBar(ctx)

                // 列表表头（解读标签：名称 代码 最新价 涨跌额 涨跌幅，与列表行 5 列对齐）
                listHeaderRow()

                // 股票列表（内部已包含 loading / error / empty / list 的响应式切换）
                stockListView(ctx)

                // 加载更多按钮（非加载中、非失败、列表有数据且还有更多时显示；点击后 isLoading 变 true 自动隐藏，避免重复点击）
                vif({ !ctx.isLoading && !ctx.loadError && ctx.stockList.isNotEmpty() && ctx.hasMore }) {
                    loadMoreButton(ctx)
                }
                // 没有更多数据提示（置灰不可点击，条件直接读 observable 保证 hasMore 变化后响应式切换）
                vif({ !ctx.isLoading && !ctx.loadError && ctx.stockList.isNotEmpty() && !ctx.hasMore }) {
                    noMoreButton()
                }

                // 独立提示弹窗（悬浮在页面顶部，不占布局、不挤页面，点击或自动消失）
                vif({ ctx.hint.isNotEmpty() }) {
                    hintPopup(ctx)
                }
            }
        }
    }

    override fun didInit() {
        super.didInit()
        // 首屏加载股票列表数据
        refreshData()
    }

    // ==================== 数据操作方法 ====================

    /**
     * 刷新数据（首屏由 didInit 触发，也可点刷新按钮触发）
     * 调用后端 GET /api/v1/stocks
     */
    internal fun refreshData() {
        if (isLoading) return
        showHint("正在刷新…")
        currentPage = 1
        currentKeyword = searchKeyword
        loadStockList(isRefresh = true)
    }

    /**
     * 搜索股票
     */
    internal fun searchStocks() {
        if (isLoading) return
        showHint("正在搜索…")
        currentKeyword = searchKeyword
        currentPage = 1
        loadStockList(isRefresh = true)
    }

    /**
     * 加载更多
     */
    internal fun loadMore() {
        if (isLoading || loadError) return
        showHint("正在加载更多…")
        currentPage++
        loadStockList(isRefresh = false)
    }

    /**
     * 切换排序方式：更新选中态并重新加载列表
     * @param option 排序标签文案（默认 / 涨幅 / 跌幅 / 成交量）
     */
    internal fun applySort(option: String) {
        if (isLoading) return
        if (sortOption == option) return
        sortOption = option
        showHint("正在按「$option」排序…")
        currentPage = 1
        currentKeyword = searchKeyword
        loadStockList(isRefresh = true)
    }

    /** 当前排序参数映射：返回 (sort 列, order 方向)，null 表示默认排序 */
    private fun sortParams(): Pair<String?, String?> {
        return when (sortOption) {
            "涨幅" -> "change_percent" to "desc"
            "跌幅" -> "change_percent" to "asc"
            "成交量" -> "volume" to "desc"
            else -> null to null
        }
    }

    /**
     * 返回按钮行为：若处于搜索/排序状态，先回到【大盘行情】默认首页（清搜索+默认排序），
     * 再次返回才退出页面回到对话；未搜索/未排序时直接关闭页面。
     */
    internal fun backToDefaultList() {
        val filtered = searchKeyword.isNotEmpty() || sortOption != "默认"
        if (filtered) {
            // 回到默认列表：清空搜索词与排序，重新加载全量列表
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

    /** 显示临时提示（1.2 秒后自动消失，首屏"共 N 条"浮窗不长时间遮挡列表） */
    internal fun showHint(msg: String) {
        hint = msg
        lifecycleScope.launch {
            delay(1200)
            if (hint == msg) hint = ""
        }
    }

    /**
     * 真正的数据加载封装
     * 优先使用本地数据（离线），网络作为可选补充
     * @param isRefresh 是否清空已有列表（首屏/刷新/搜索为 true，加载更多为 false）
     */
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
                // 统一走 StockRepository（按开发者选项路由离线/在线），排序参数一并下发，顺带拿匹配总数
                val (sortCol, sortDir) = sortParams()
                val (total, localList) = StockRepository.loadStockListWithTotal(
                    currentKeyword.ifBlank { null }, sortCol, sortDir
                )

                // 分页截取（每页20条）
                val startIdx = if (isRefresh) 0 else stockList.size
                val pageItems = localList.drop(startIdx).take(20)

                // 网络调用恢复在 OkHttp 线程，用 Kuikly delay(0) 切回渲染线程再更新 observable
                delay(0)
                totalCount = total
                if (pageItems.isNotEmpty()) {
                    stockList.addAll(pageItems)
                    hasMore = pageItems.size >= 20
                    // 走 showHint（1.2 秒自动消失），不能直接赋值 hint——否则浮窗一直停留必须点击才消失
                    showHint(if (isRefresh) "已刷新，共 ${stockList.size} 只股票" else "已加载 ${pageItems.size} 条")
                } else if (stockList.isEmpty()) {
                    // 搜索无结果：不置 loadError（避免"加载失败"误导用户），
                    // 保持空列表走 emptyView（"暂无数据，请尝试其他搜索条件或刷新重试"）
                    hasMore = false
                    showHint("未找到相关股票")
                } else {
                    hasMore = false
                    showHint("没有更多了")
                }
            } catch (e: Throwable) {
                delay(0)  // 网络异常在 OkHttp 线程抛出，先切回渲染线程再更新 observable
                loadError = true
                loadErrorMessage = e.message ?: "数据加载失败"
                showHint("加载失败：" + (e.message ?: "未知错误"))
            } finally {
                isLoading = false
            }
        }
    }
}

// ==================== 顶部扩展函数 ====================

/**
 * 导航栏
 */
internal fun ViewContainer<*, *>.navigationBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(0xFF1976D2)
            paddingTop(ctx.pagerData.statusBarHeight)
            height(48f + ctx.pagerData.statusBarHeight)
        }

        // 返回按钮（搜索/排序状态下先回到默认首页，再返回才退出页面）
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

        // 标题
        Text {
            attr {
                text("股票行情")
                fontSize(17f)
                fontWeightBold()
                color(0xFFFFFFFF)
                marginLeft(8f)
            }
        }

        // 总数（vif 条件直接读 observable：数据加载完成后切换显示总条数）
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

        // 刷新按钮
        View {
            attr { padding(10f, 12f, 10f, 12f) }
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

/**
 * 搜索栏
 */
internal fun ViewContainer<*, *>.searchBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(0xFFFFFFFF)
        }

        // 搜索图标 + 输入框（原生 Input：实时更新关键词，回车直接搜索；
        // 占位文字用覆盖层实现——Kuikly Input 的 placeholder 在部分 Android 版本渲染不可靠）
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
                // 官方推荐用 ref 回调保存视图引用（flex 布局下必须显式 height 才会创建原生输入视图）
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
                    // 注册焦点/失焦事件，确保组件接收触摸并弹出键盘
                    inputFocus { println("[Input] inputFocus fired") }
                    // 输入过程中实时同步关键词（isSyncEdit 避免异步跳变）
                    textDidChange(isSyncEdit = true) { params ->
                        println("[Input] textDidChange text=" + params.text)
                        ctx.searchKeyword = params.text
                    }
                    // 软键盘搜索键触发搜索
                    inputReturn { params ->
                        ctx.searchKeyword = params.text
                        ctx.searchStocks()
                    }
                }
            }

            // 占位文字覆盖层：关键词为空时显示，点击聚焦输入框弹出键盘
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
                            println("[Input] overlay click -> focus()")
                            ctx.searchInput?.focus() ?: println("[Input] searchInput is NULL")
                        }
                    }
                }
            }
        }

        // 搜索按钮
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

/**
 * 排序标签栏
 */
internal fun ViewContainer<*, *>.sortBar(ctx: StockListPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(0xFFFFFFFF)
        }

        listOf("默认", "涨幅", "跌幅", "成交量").forEach { option ->
            // 每个标签渲染选中/未选中两个视图，vif 条件直接读 ctx.sortOption（observable 响应式求值，
            // 排序切换后选中态随之刷新）
            vif({ ctx.sortOption == option }) {
                sortChipView(ctx, option, selected = true)
            }
            vif({ ctx.sortOption != option }) {
                sortChipView(ctx, option, selected = false)
            }
        }

        View { attr { flex(1f) } }

        // 颜色图例：红色=上涨，绿色=下跌，灰色=平盘
        legendItem(0xFFE53935, "涨")
        legendItem(0xFF43A047, "跌")
        legendItem(0xFF999999, "平")
    }
}

/**
 * 颜色图例单项（色块 + 文字）
 */
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

/**
 * 列表表头（解读标签行）：名称 代码 最新价 涨跌额 涨跌幅，固定列宽与列表行一一对应
 */
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

/**
 * 排序标签（selected 决定高亮样式）
 */
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

/**
 * 股票列表视图
 */
internal fun ViewContainer<*, *>.stockListView(ctx: StockListPage) {
    Scroller {
        attr {
            flex(1f)
            flexDirectionColumn()
            scrollEnable(true)
        }
        // 使用 Kuikly 条件指令实现 loading / error / empty / list 的响应式切换
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
            // 使用 vfor 让股票列表响应式增删
            vfor({ ctx.stockList }) { stock ->
                stockListItem(ctx, stock)
            }
            vif({ ctx.isLoading }) {
                loadingMoreView()
            }
        }
    }
}

/**
 * 单个股票列表项
 *
 * 布局：5 列固定宽度（与表头 listHeaderRow 一一对应）：
 *   名称(flex1) | 代码(64) | 最新价(70右) | 涨跌额(58右) | 涨跌幅(66右)
 * 颜色约定：上涨红色、下跌绿色、平盘/无数据灰色
 */
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

        // 名称（命中搜索关键词的片段标红）
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

        // 代码（命中关键词片段标红）
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

        // 最新价
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

        // 涨跌额
        Text {
            attr {
                text(changeText)
                fontSize(12f)
                color(changeColor)
                width(56f)
                textAlignRight()
            }
        }

        // 涨跌幅
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
 * 加载中视图
 */
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

/**
 * 空状态视图
 */
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

/**
 * 加载失败视图
 */
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
        // 重试按钮
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

/**
 * 独立提示弹窗（悬浮在页面顶部中央，深色圆角卡片，点击或 2 秒后自动消失）
 * 只占顶部一条区域，不遮挡列表操作，避免与页面内容挤在一起。
 */
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

/**
 * 加载更多指示器
 */
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

/**
 * 加载更多按钮
 */
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

/**
 * 没有更多数据提示（置灰、不可点击）
 */
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

/**
 * 股票列表项数据类
 * @param change 涨跌额（最新价 - 昨收）
 * @param volume 成交量（手），用于"成交量"排序
 */
data class StockListItem(
    val code: String,
    val name: String? = null,
    val price: Double? = null,
    val changePercent: Double? = null,
    val change: Double? = null,
    val volume: Double? = null
)

/**
 * 关键词高亮分段：把文本按搜索关键词切成 (片段, 是否命中) 列表，命中片段渲染为红色。
 * 支持单字/多字子串匹配（忽略大小写），命中位置不区分大小写但输出保留原文。
 */
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
