package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
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
import com.tencent.kuikly.core.views.*
import com.kuikly.stock.data.StockRepository
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch

/**
 * 个股详情页
 *
 * 功能：
 * 1. 展示个股基础信息（名称、代码、行业等）
 * 2. 展示实时行情（最新价、涨跌幅、最高/最低价、成交量等）
 * 3. 展示 K 线图表区域（简化版，实际项目应集成图表库）
 * 4. 展示 AI 解读卡片（趋势判断、技术信号、风险评估、操作建议、总结）
 *
 * 可从两个通路跳转过来：
 * - 通路一：行情列表页点击某只股票
 * - 通路二：AI 聊天中的股票卡片点击
 */
@Page("stock_detail")
class StockDetailPage : Pager() {

    // 状态：股票代码（从路由参数获取）
    internal var stockCode by observable("")

    // 状态：股票详情数据
    internal var stockDetail by observable<StockDetailData?>(null)

    // 状态：AI 分析结果
    internal var aiAnalysis by observable<AIAnalysisData?>(null)

    // 状态：是否正在加载
    internal var isLoading by observable(true)

    // 状态：是否正在加载 AI 分析
    internal var isAnalyzing by observable(false)

    // 状态：分时数据（在线才有，离线为 null）
    internal var minuteData by observable<List<MinutePoint>?>(null)

    // 状态：五档盘口（在线才有，仅部分热门股）
    internal var orderBook by observable<OrderBookData?>(null)

    // 状态：加载失败的错误信息
    internal var loadErrorMessage by observable("")

    override fun didInit() {
        super.didInit()
        // 从路由参数中获取股票代码
        stockCode = pagerData.params.optString("code", "")

        // 加载数据
        if (stockCode.isNotEmpty()) {
            loadStockDetail()
            loadExtraQuote()
        }
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
                    // 顶部导航栏
                    detailNavigationBar(ctx)

                    // 内容区域（可滚动）
                    Scroller {
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            scrollEnable(true)
                        }

                        // 基础信息卡片
                        infoCard(ctx)

                        // 实时行情卡片
                        realtimeCard(ctx)

                        // 技术指标卡片（来自 stock_indicator）
                        indicatorCard(ctx)

                        // K线图表区域
                        klineChartArea(ctx)

                        // 分时数据卡片（在线才有）
                        minuteCard(ctx)

                        // 五档盘口卡片（在线才有，仅部分热门股）
                        orderBookCard(ctx)

                        // AI 解读卡片区域
                        aiAnalysisCards(ctx)
                    }
                }
            }
        }
    }

    // ==================== 数据加载方法 ====================

    /**
     * 加载股票详情
     * 统一走 StockRepository（按开发者选项路由离线/在线）
     */
    internal fun loadStockDetail() {
        if (stockCode.isEmpty()) return
        isLoading = true

        lifecycleScope.launch {
            try {
                // 统一走 StockRepository（按开发者选项路由离线/在线）
                val data = StockRepository.loadStockDetail(stockCode)
                // 网络调用恢复在 OkHttp 线程，用 Kuikly delay(0) 切回渲染线程再更新 observable
                delay(0)
                if (data != null) {
                    stockDetail = data
                } else {
                    stockDetail = null
                    loadErrorMessage = "未找到股票 $stockCode 的数据"
                }
            } catch (e: Throwable) {
                delay(0)  // 网络异常在 OkHttp 线程抛出，先切回渲染线程再更新 observable
                stockDetail = null
                loadErrorMessage = e.message ?: "数据加载失败"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 触发 AI 分析
     * 优先调用后端真实 AI（基于数据文件夹作答）；后端不可达自动回退本地模板分析
     */
    internal fun triggerAIAnalysis() {
        if (stockCode.isEmpty()) return
        isAnalyzing = true
        lifecycleScope.launch {
            val result = StockRepository.analyzeStock(stockCode)
            delay(0)
            aiAnalysis = result
            isAnalyzing = false
        }
    }

    /**
     * 加载分时与盘口数据（仅在线；离线返回 null，对应卡片不显示）
     */
    internal fun loadExtraQuote() {
        if (stockCode.isEmpty()) return
        lifecycleScope.launch {
            try {
                val minute = StockRepository.loadMinute(stockCode)
                delay(0)
                minuteData = minute
                val book = StockRepository.loadOrderBook(stockCode)
                delay(0)
                orderBook = book
            } catch (e: Throwable) {
                delay(0)
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取模拟股票名称（仅在数据缺失时兜底显示）
     */
    private fun getMockName(code: String): String {
        return when (code) {
            "000001" -> "平安银行"
            "600519" -> "贵州茅台"
            "000002" -> "万科A"
            "600036" -> "招商银行"
            "300750" -> "宁德时代"
            else -> "未知股票"
        }
    }
}

// ==================== 子视图构建函数（顶层扩展函数，可被 body 的 lambda 直接调用） ====================

/**
 * 详情页导航栏
 */
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

        // 股票名称和代码
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

        // AI 分析按钮
        View {
            attr { padding(10f, 12f, 10f, 12f) }
            event { click { ctx.triggerAIAnalysis() } }
            Text {
                attr {
                    text("AI分析")
                    fontSize(13f)
                    color(0xFFFFFFFF)
                }
            }
        }
    }
}

/**
 * 基础信息卡片
 */
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

        // 标题
        Text {
            attr {
                text("基础信息")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

        // 信息项列表
        infoItem("股票代码", info.code)
        infoItem("股票名称", info.name ?: "-")
        infoItem("所属行业", info.industry ?: "-")
        infoItem("市场板块", info.plate ?: "-")
        infoItem("上市日期", info.listDate ?: "-")
    }
}

/**
 * 信息项
 */
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

/**
 * 实时行情卡片
 */
internal fun ViewContainer<*, *>.realtimeCard(ctx: StockDetailPage) {
    val realtime = ctx.stockDetail?.realtime ?: return
    val isPositive = (realtime.changePercent ?: 0.0) >= 0
    val priceColor = if (isPositive) 0xFFE53935 else 0xFF43A047

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        // 标题
        Text {
            attr {
                text("实时行情")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

        // 价格和涨跌幅（大字显示）
        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.FLEX_END)
                marginBottom(8f)
            }

            Text {
                attr {
                    text(realtime.price?.let { String.format("%.2f", it) } ?: "-")
                    fontSize(28f)
                    fontWeightBold()
                    color(priceColor)
                }
            }

            Text {
                attr {
                    text(realtime.changePercent?.let { String.format("%.2f%%", it) } ?: "-")
                    fontSize(16f)
                    fontWeightBold()
                    color(priceColor)
                    marginLeft(8f)
                }
            }

            Text {
                attr {
                    text(realtime.change?.let { String.format("%.2f", it) } ?: "-")
                    fontSize(14f)
                    color(priceColor)
                    marginLeft(4f)
                }
            }
        }

        // 详细行情数据网格
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

/**
 * 行情数据项
 */
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
                text(value?.let { String.format("%.2f", it) } ?: "-$suffix")
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
    }
}

/**
 * 技术指标卡片（MA / MACD / RSI / KDJ）
 */
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

        Text {
            attr {
                text("技术指标（${ind.tradeDate}）")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

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

private fun fmtInd(v: Double?): String = if (v == null) "-" else String.format("%.3f", v)

/**
 * 分时数据卡片（在线才有）
 */
internal fun ViewContainer<*, *>.minuteCard(ctx: StockDetailPage) {
    val data = ctx.minuteData ?: return

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
                text("分时数据（共 ${data.size} 分钟）")
                fontSize(15f); fontWeightBold(); color(0xFF333333); marginBottom(8f)
            }
        }

        val latest = data.lastOrNull()
        if (latest != null) {
            View {
                attr { flexDirectionRow() }
                Text { attr { text("最新分时"); fontSize(12f); color(0xFF666666); width(72f) } }
                Text {
                    attr {
                        text("${latest.time}  价 ${String.format("%.2f", latest.price)}  均价 ${fmtOpt(latest.avgPrice)}")
                        fontSize(12f); color(0xFF333333)
                    }
                }
            }
            View {
                attr { flexDirectionRow(); marginTop(4f) }
                Text { attr { text("区间"); fontSize(12f); color(0xFF666666); width(72f) } }
                val prices = data.map { it.price }
                val hi = prices.maxOrNull(); val lo = prices.minOrNull()
                Text {
                    attr { text("高 ${fmtOpt(hi)}  低 ${fmtOpt(lo)}"); fontSize(12f); color(0xFF333333) }
                }
            }
        }
        Text {
            attr { text("提示：分时仅 11 只热门股有数据"); fontSize(11f); color(0xFF999999); marginTop(6f) }
        }
    }
}

/**
 * 五档盘口卡片（在线才有，仅部分热门股）
 */
internal fun ViewContainer<*, *>.orderBookCard(ctx: StockDetailPage) {
    val book = ctx.orderBook ?: return

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
                text("五档盘口${book.updateTime?.let { "（$it）" } ?: ""}")
                fontSize(15f); fontWeightBold(); color(0xFF333333); marginBottom(8f)
            }
        }

        book.asks.reversed().forEachIndexed { i, (price, vol) ->
            orderBookRow("卖${5 - i}", price, vol, 0xFF43A047)
        }
        View { attr { height(1f); backgroundColor(0xFFEEEEEE); margin(4f, 0f, 4f, 0f) } }
        book.bids.forEachIndexed { i, (price, vol) ->
            orderBookRow("买${i + 1}", price, vol, 0xFFE53935)
        }

        book.commissionRatio?.let { ratio ->
            Text {
                attr { text("委比 ${String.format("%.2f", ratio)}%"); fontSize(12f); color(0xFF666666); marginTop(6f) }
            }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookRow(label: String, price: Double?, vol: Double?, color: Long) {
    View {
        attr { flexDirectionRow(); marginTop(3f) }
        Text { attr { text(label); fontSize(12f); color(0xFF666666); width(40f) } }
        Text { attr { text(fmtOpt(price)); fontSize(12f); color(color); flex(1f) } }
        Text { attr { text(fmtOpt(vol)); fontSize(12f); color(0xFF666666) } }
    }
}

private fun fmtOpt(v: Double?): String = if (v == null) "-" else String.format("%.2f", v)

/**
 * K线图表区域
 */
internal fun ViewContainer<*, *>.klineChartArea(ctx: StockDetailPage) {
    val klineData = ctx.stockDetail?.kline

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        // 标题
        Text {
            attr {
                text("K线走势（近30日）")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

        vif({ klineData != null && klineData.isNotEmpty() }) {
            // 图表占位符（实际项目应使用图表库渲染）
            chartPlaceholder(klineData!!)

            // 最新几条K线数据摘要
            klineSummary(klineData)
        }
        velse {
            // 无数据提示
            View {
                attr {
                    padding(top = 20f, left = 0f, bottom = 20f, right = 0f)
                }
                Text {
                    attr {
                        text("暂无K线数据")
                        fontSize(13f)
                        color(0xFF999999)
                        textAlignCenter()
                    }
                }
            }
        }
    }
}

/**
 * 图表占位符
 */
internal fun ViewContainer<*, *>.chartPlaceholder(klineData: List<KLineDataItem>) {
    View {
        attr {
            height(200f)
            backgroundColor(0xFFF9F9F9)
            borderRadius(8f)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }

        Text {
            attr {
                text("[K线图表区域] 展示 ${klineData.size} 条K线数据")
                fontSize(12f)
                color(0xFF999999)
                textAlignCenter()
            }
        }
    }
}

/**
 * K线数据摘要
 */
internal fun ViewContainer<*, *>.klineSummary(klineData: List<KLineDataItem>) {
    val latest = klineData.lastOrNull()
    if (latest == null) return

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
        }

        // 分割线（替代原 borderTop）
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
            }

            Text {
                attr {
                    text("最新: ${latest.tradeDate}")
                    fontSize(12f)
                    color(0xFF666666)
                }
            }

            Text {
                attr {
                    text("收盘: ${latest.close}")
                    fontSize(12f)
                    fontWeightBold()
                    color(0xFF333333)
                    marginLeft(12f)
                }
            }

            Text {
                attr {
                    text("成交量: ${latest.volume.toInt()} 手")
                    fontSize(12f)
                    color(0xFF666666)
                    marginLeft(12f)
                }
            }
        }
    }
}

/**
 * AI 解读卡片区域
 */
internal fun ViewContainer<*, *>.aiAnalysisCards(ctx: StockDetailPage) {
    val analysis = ctx.aiAnalysis

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 12f, 12f)
        }

        // 标题栏
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
                }
            }

            vif({ !ctx.isAnalyzing }) {
                View {
                    attr {
                        marginLeft(8f)
                        padding(top = 4f, left = 8f, bottom = 4f, right = 8f)
                        backgroundColor(0xFFFFF9C4)
                        borderRadius(12f)
                    }
                    event {
                        click {
                            ctx.triggerAIAnalysis()
                        }
                    }
                    Text {
                        attr {
                            text("刷新分析")
                            fontSize(11f)
                            color(0xFF666666)
                        }
                    }
                }
            }
        }

        vif({ ctx.isAnalyzing }) {
            // 分析中状态
            analyzingView()
        }
        velseif({ analysis == null }) {
            // 未分析状态
            notAnalyzedView(ctx)
        }
        velse {
            // 显示分析卡片（普通 List 需包成 ObservableList 才能给 vfor 用）
            vfor({ ObservableList(analysis!!.cards.toMutableList()) }) { card ->
                renderAICard(card)
            }
        }
    }
}

/**
 * 渲染单个 AI 卡片
 */
internal fun ViewContainer<*, *>.renderAICard(card: Map<String, Any?>) {
    val type = card["type"] as? String ?: ""
    val title = card["title"] as? String ?: ""
    val content = card["content"] as? String ?: ""
    val color = card["color"] as? String ?: "#FF6B6B"

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
            padding(top = 10f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(0xFFFFF9C4)
            borderRadius(8f)
        }

        // 卡片标题和图标
        val icon = when (type) {
            "trend_card" -> "[趋势]"
            "signal_card" -> "[信号]"
            "risk_card" -> "[风险]"
            "suggestion_card" -> "[建议]"
            "summary_card" -> "[总结]"
            else -> "[卡片]"
        }

        Text {
            attr {
                text("$icon $title")
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
            }
        }

        // 卡片内容
        when (type) {
            "signal_card" -> {
                // 信号列表
                val signals = card["signals"] as? List<*> ?: emptyList<Any>()
                signals.forEach { signal ->
                    Text {
                        attr {
                            text("• $signal")
                            fontSize(12f)
                            color(0xFF555555)
                            marginTop(2f)
                        }
                    }
                }
            }
            "suggestion_card" -> {
                // 建议详情
                Text {
                    attr {
                        text("建议: ${card["suggestion"] ?: "-"}\n目标: ${card["target_price"] ?: "-"}\n止损: ${card["stop_loss"] ?: "-"}")
                        fontSize(12f)
                        color(0xFF555555)
                        marginTop(4f)
                    }
                }
            }
            else -> {
                // 默认文本内容
                Text {
                    attr {
                        text(content)
                        fontSize(12f)
                        color(0xFF555555)
                        marginTop(4f)
                    }
                }
            }
        }
    }
}

/**
 * 分析中视图
 */
internal fun ViewContainer<*, *>.analyzingView() {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 20f, left = 16f, bottom = 20f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
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
                text("请稍候，DeepSeek 正在为您生成专业分析报告")
                fontSize(12f)
                color(0xFF999999)
                marginTop(8f)
            }
        }
    }
}

/**
 * 未分析视图
 */
internal fun ViewContainer<*, *>.notAnalyzedView(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 20f, left = 16f, bottom = 20f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        Text {
            attr {
                text("尚未进行 AI 分析")
                fontSize(14f)
                color(0xFF666666)
            }
        }

        // 触发分析按钮
        View {
            attr {
                marginTop(12f)
                padding(top = 10f, left = 24f, bottom = 10f, right = 24f)
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

        Text {
            attr {
                text("AI 将为您分析趋势、信号、风险并给出操作建议")
                fontSize(11f)
                color(0xFF999999)
                marginTop(8f)
            }
        }
    }
}

/**
 * 加载中视图
 */
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

/**
 * 错误视图
 */
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

// ==================== 数据类定义 ====================

/**
 * 股票基础信息（简化版）
 */
data class StockInfoData(
    val code: String,
    val name: String?,
    val industry: String?,
    val plate: String?,
    val listDate: String?
)

/**
 * 实时行情（简化版）
 */
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

/**
 * 技术指标（简化版）
 */
data class IndicatorData(
    val tradeDate: String,
    val ma5: Double?, val ma10: Double?, val ma20: Double?,
    val dif: Double?, val dea: Double?, val macd: Double?,
    val rsi6: Double?,
    val kdjK: Double?, val kdjD: Double?, val kdjJ: Double?
)

/**
 * K线数据项（简化版）
 */
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

/**
 * 分时数据点（简化版）
 */
data class MinutePoint(
    val time: String,
    val price: Double,
    val avgPrice: Double?,
    val volume: Double?
)

/**
 * 五档盘口（简化版）
 */
data class OrderBookData(
    val updateTime: String?,
    val bids: List<Pair<Double?, Double?>>,   // 买1~5 (price, vol)
    val asks: List<Pair<Double?, Double?>>,   // 卖1~5
    val commissionRatio: Double?
)

/**
 * 股票详情聚合数据
 */
data class StockDetailData(
    val info: StockInfoData?,
    val realtime: RealtimeQuoteData?,
    val kline: List<KLineDataItem>?,
    val indicator: IndicatorData? = null
)

/**
 * AI 分析结果数据
 */
data class AIAnalysisData(
    val code: String,
    val name: String?,
    val analysis: Map<String, Any?>,
    val cards: List<Map<String, Any?>>
)
