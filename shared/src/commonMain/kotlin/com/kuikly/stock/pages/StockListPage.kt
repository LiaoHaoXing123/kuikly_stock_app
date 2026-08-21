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

                // 股票列表（内部已包含 loading / error / empty / list 的响应式切换）
                stockListView(ctx)

                // 加载更多按钮（非加载中、非失败、列表有数据且还有更多时显示）
                vif({ !ctx.isLoading && !ctx.loadError && ctx.stockList.isNotEmpty() && ctx.hasMore }) {
                    loadMoreButton(ctx)
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

    /** 显示临时提示（2 秒后自动消失） */
    internal fun showHint(msg: String) {
        hint = msg
        lifecycleScope.launch {
            delay(2000)
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
                // 统一走 StockRepository（按开发者选项路由离线/在线）
                val localList = StockRepository.loadStockList(currentKeyword.ifBlank { null })

                // 分页截取（每页20条）
                val startIdx = if (isRefresh) 0 else stockList.size
                val pageItems = localList.drop(startIdx).take(20)

                // 网络调用恢复在 OkHttp 线程，用 Kuikly delay(0) 切回渲染线程再更新 observable
                delay(0)
                if (pageItems.isNotEmpty()) {
                    stockList.addAll(pageItems)
                    hasMore = pageItems.size >= 20
                    hint = if (isRefresh) "已刷新，共 ${stockList.size} 只股票"
                        else "已加载 ${pageItems.size} 条"
                } else if (stockList.isEmpty()) {
                    loadError = true
                    loadErrorMessage = "暂无数据"
                    hint = "未找到相关股票"
                } else {
                    hasMore = false
                    hint = "没有更多了"
                }
            } catch (e: Throwable) {
                delay(0)  // 网络异常在 OkHttp 线程抛出，先切回渲染线程再更新 observable
                loadError = true
                loadErrorMessage = e.message ?: "数据加载失败"
                hint = "加载失败：" + (e.message ?: "未知错误")
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

        // 返回按钮
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

        Text {
            attr {
                text("共 ${ctx.stockList.size} 只")
                fontSize(12f)
                color(0xFFB3D9FF)
                marginLeft(6f)
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

        // 搜索图标 + 输入框
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

            Text {
                attr {
                    text(ctx.searchKeyword.ifEmpty { "搜索股票代码或名称..." })
                    fontSize(14f)
                    color(if (ctx.searchKeyword.isEmpty()) 0xFF999999 else 0xFF333333)
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
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(0xFFFFFFFF)
        }

        listOf("默认", "涨幅", "跌幅", "成交量").forEach { option ->
            sortChip(ctx, option)
        }
    }
}

/**
 * 排序标签
 */
internal fun ViewContainer<*, *>.sortChip(ctx: StockListPage, option: String) {
    View {
        attr {
            marginRight(8f)
            padding(left = 10f, top = 4f, right = 10f, bottom = 4f)
            backgroundColor(0xFFE3F2FD)
            borderRadius(12f)
        }
        event {
            click {
                // TODO: 按该选项排序
            }
        }
        Text {
            attr {
                text(option)
                fontSize(12f)
                color(0xFF1976D2)
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
 */
internal fun ViewContainer<*, *>.stockListItem(
    ctx: StockListPage,
    stock: StockListItem
) {
    val isPositive = (stock.changePercent ?: 0.0) >= 0
    val changeColor = if (isPositive) 0xFFE53935 else 0xFF43A047

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

        // 左侧：股票名称和代码
        View {
            attr { flex(1f) }
            Text {
                attr {
                    text(stock.name ?: "未知")
                    fontSize(15f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }
            Text {
                attr {
                    text(stock.code)
                    fontSize(12f)
                    color(0xFF888888)
                    marginTop(2f)
                }
            }
        }

        // 中间：最新价
        Text {
            attr {
                text("${stock.price ?: "-"}")
                fontSize(16f)
                fontWeightBold()
                color(changeColor)
                textAlignRight()
                marginLeft(12f)
            }
        }

        Text {
            attr {
                text(stock.changePercent?.let { String.format("%.2f%%", it) } ?: "-")
                fontSize(12f)
                color(changeColor)
                marginLeft(8f)
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
 * 股票列表项数据类
 */
data class StockListItem(
    val code: String,
    val name: String?,
    val price: Double?,
    val changePercent: Double?
)
