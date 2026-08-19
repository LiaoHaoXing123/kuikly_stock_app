package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable

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

    // 状态：股票列表数据
    internal var stockList by observable(mutableListOf<StockListItem>())

    // 状态：搜索关键词
    internal var searchKeyword by observable("")

    // 状态：当前页码
    internal var currentPage by observable(1)

    // 状态：是否正在加载
    internal var isLoading by observable(false)

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

                // 股票列表
                stockListView(ctx)

                // 加载更多按钮
                if (!ctx.isLoading && ctx.stockList.isNotEmpty()) {
                    loadMoreButton(ctx)
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
     */
    internal fun refreshData() {
        currentPage = 1
        stockList = loadMockData()
    }

    /**
     * 搜索股票
     */
    internal fun searchStocks() {
        stockList = loadMockData()
    }

    /**
     * 加载更多
     */
    internal fun loadMore() {
        if (isLoading) return
        isLoading = true
        currentPage++
        stockList = (stockList + loadMockData()).toMutableList()
        isLoading = false
    }

    /**
     * 加载模拟数据（开发测试用）
     * 实际项目中应替换为真实 API 调用
     */
    internal fun loadMockData(): MutableList<StockListItem> {
        return mutableListOf(
            StockListItem(code = "000001", name = "平安银行", price = 11.05, changePercent = 1.20),
            StockListItem(code = "600519", name = "贵州茅台", price = 1685.00, changePercent = -0.50),
            StockListItem(code = "000002", name = "万科A", price = 8.92, changePercent = 2.30),
            StockListItem(code = "600036", name = "招商银行", price = 35.68, changePercent = 0.85),
            StockListItem(code = "300750", name = "宁德时代", price = 218.50, changePercent = -1.25),
            StockListItem(code = "601318", name = "中国平安", price = 48.32, changePercent = 1.05),
            StockListItem(code = "000858", name = "五粮液", price = 152.80, changePercent = -0.35),
            StockListItem(code = "002594", name = "比亚迪", price = 256.90, changePercent = 3.20),
            StockListItem(code = "600900", name = "长江电力", price = 28.45, changePercent = 0.15),
            StockListItem(code = "300001", name = "特锐德", price = 22.18, changePercent = -2.10)
        )
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
            height(48f)
            backgroundColor(0xFF1976D2)
        }

        // 返回按钮
        View {
            attr { padding(left = 16f, top = 12f, right = 16f, bottom = 12f) }
            event {
                click {
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                }
            }
            Text {
                attr {
                    text("‹ 返回")
                    fontSize(16f)
                    color(0xFFFFFFFF)
                }
            }
        }

        // 标题
        Text {
            attr {
                text("📈 股票行情\n共 ${ctx.stockList.size} 只")
                fontSize(16f)
                fontWeightBold()
                color(0xFFFFFFFF)
                marginLeft(8f)
            }
        }

        View { attr { flex(1f) } }

        // 刷新按钮
        View {
            attr { padding(left = 12f, top = 12f, right = 12f, bottom = 12f) }
            event {
                click { ctx.refreshData() }
            }
            Text {
                attr {
                    text("🔄\n刷新")
                    fontSize(12f)
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
                    text("🔍 ${ctx.searchKeyword.ifEmpty { "搜索股票代码或名称..." }}")
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
                    text("搜索\nSearch")
                    fontSize(12f)
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
        val scroller = this
        attr {
            flex(1f)
            flexDirectionColumn()
            scrollEnable(true)
        }
        with(scroller) {
            if (ctx.isLoading && ctx.stockList.isEmpty()) {
                stockListLoadingView()
            } else if (ctx.stockList.isEmpty()) {
                emptyView()
            } else {
                ctx.stockList.forEach { stock ->
                    stockListItem(ctx, stock)
                }
                if (ctx.isLoading) {
                    loadingMoreView()
                }
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
                    text("${stock.name ?: "未知"}\n${stock.code}")
                    fontSize(15f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }
        }

        // 中间：最新价
        Text {
            attr {
                text("${stock.price ?: "-"}\n${stock.changePercent?.let { String.format("%.2f%%", it) } ?: "-"}")
                fontSize(15f)
                fontWeightBold()
                color(changeColor)
                textAlignRight()
                marginLeft(12f)
            }
        }

        // 右侧：箭头
        Text {
            attr {
                text("›\n ")
                fontSize(20f)
                color(0xFFCCCCCC)
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
                text("⏳ 加载中...")
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
                text("📭 暂无数据\n\n请尝试其他搜索条件或刷新重试")
                fontSize(14f)
                color(0xFF999999)
                textAlignCenter()
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
                text("⏳ 加载更多...")
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
                text("📄 加载更多\nLoad More")
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
