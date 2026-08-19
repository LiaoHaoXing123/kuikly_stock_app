package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

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
    internal var stockDetail: StockDetailData? = null

    // 状态：AI 分析结果
    internal var aiAnalysis: AIAnalysisData? = null

    // 状态：是否正在加载
    internal var isLoading by observable(true)

    // 状态：是否正在加载 AI 分析
    internal var isAnalyzing by observable(false)

    override fun didInit() {
        super.didInit()
        // 从路由参数中获取股票代码
        val params = pagerData.params
        if (params != null) {
            stockCode = params.optString("code", "")
        }

        // 加载数据
        if (stockCode.isNotEmpty()) {
            loadStockDetail()
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

                if (ctx.isLoading) {
                    stockDetailLoadingView()
                } else if (ctx.stockDetail == null) {
                    errorView(ctx)
                } else {
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

                        // K线图表区域
                        klineChartArea(ctx)

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
     */
    internal fun loadStockDetail() {
        isLoading = true

        // TODO: 调用后端 API
        // GET /api/v1/stocks/{code}/detail

        // 模拟延迟
        // Thread.sleep(800)

        // 模拟数据（开发测试用）
        stockDetail = StockDetailData(
            info = StockInfoData(
                code = stockCode,
                name = getMockName(stockCode),
                industry = "银行",
                plate = if (stockCode.startsWith("6")) "沪市" else "深市",
                listDate = "1991-04-03"
            ),
            realtime = RealtimeQuoteData(
                code = stockCode,
                name = getMockName(stockCode),
                price = 11.05,
                change = 0.13,
                changePercent = 1.20,
                openPrice = 11.00,
                preClose = 10.92,
                high = 11.14,
                low = 11.01,
                volume = 808930.0,
                amount = 896000000.0,
                peTtm = 5.23,
                pb = 0.62
            ),
            kline = generateMockKlineData(stockCode, 30)
        )

        isLoading = false

        // TODO: 通知 UI 刷新
    }

    /**
     * 触发 AI 分析
     */
    internal fun triggerAIAnalysis() {
        if (stockCode.isEmpty()) return

        isAnalyzing = true

        // TODO: 调用后端 AI 分析接口
        // POST /api/v1/ai/analyze/{code}

        // 模拟延迟（AI 分析通常需要 5-15 秒）
        // Thread.sleep(2000)

        // 模拟 AI 分析结果
        aiAnalysis = AIAnalysisData(
            code = stockCode,
            name = getMockName(stockCode),
            analysis = mapOf(
                "trend" to "短期震荡上行，中期看涨。股价在 10.80-11.50 区间震荡整理，成交量温和放大，显示多头力量逐步增强。",
                "support_price" to "10.80",
                "resistance_price" to "11.50",
                "risk_level" to "中等",
                "suggestion" to "持有观望",
                "target_price" to "11.80",
                "stop_loss" to "10.60",
                "summary" to "平安银行当前处于震荡上行阶段，建议持有等待突破确认，关注量能变化。"
            ),
            cards = listOf(
                mapOf(
                    "type" to "trend_card",
                    "title" to "趋势判断",
                    "content" to "短期震荡上行，中期看涨。股价在 10.80-11.50 区间震荡整理。",
                    "color" to "#FF6B6B"
                ),
                mapOf(
                    "type" to "signal_card",
                    "title" to "技术信号",
                    "signals" to listOf("MA5 金叉 MA20", "MACD 柱状图转正", "成交量温和放大"),
                    "color" to "#45B7D1"
                ),
                mapOf(
                    "type" to "risk_card",
                    "title" to "风险评估",
                    "risk_level" to "中等",
                    "risks" to listOf("大盘系统性风险", "银行业政策变化"),
                    "color" to "#FFEAA7"
                ),
                mapOf(
                    "type" to "suggestion_card",
                    "title" to "操作建议",
                    "suggestion" to "持有观望",
                    "target_price" to "11.80",
                    "stop_loss" to "10.60",
                    "support_price" to "10.80",
                    "resistance_price" to "11.50",
                    "color" to "#96CEB4"
                ),
                mapOf(
                    "type" to "summary_card",
                    "title" to "AI 总结",
                    "summary" to "当前处于震荡上行阶段，建议持有等待突破确认。",
                    "color" to "#DDA0DD"
                )
            )
        )

        isAnalyzing = false

        // TODO: 通知 UI 刷新
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取模拟股票名称
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

    /**
     * 生成模拟 K 线数据
     */
    private fun generateMockKlineData(code: String, days: Int): List<KLineDataItem> {
        val basePrice = when (code) {
            "600519" -> 1685.0
            "000001" -> 11.0
            else -> 50.0
        }

        val result = mutableListOf<KLineDataItem>()
        var price = basePrice

        for (i in 1..days) {
            val change = (Math.random() - 0.48) * basePrice * 0.02  // 倾向于上涨
            val open = price
            val close = price + change
            val high = maxOf(open, close) + Math.random() * basePrice * 0.01
            val low = minOf(open, close) - Math.random() * basePrice * 0.01
            val volume = (Math.random() * 1000000).toLong()

            result.add(KLineDataItem(
                code = code,
                tradeDate = "2026-08-${18 - i}",
                open = open,
                close = close,
                high = high,
                low = low,
                volume = volume.toDouble(),
                amount = volume.toDouble() * (open + close) / 2
            ))

            price = close
        }

        return result.reversed()  // 按日期升序
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
            height(48f)
            backgroundColor(0xFF1976D2)
        }

        // 返回按钮
        View {
            attr {
                padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            }
            event {
                click {
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                }
            }
            Text {
                attr {
                    text("‹ 返回\nBack")
                    fontSize(16f)
                    color(0xFFFFFFFF)
                }
            }
        }

        // 股票名称和代码
        Text {
            attr {
                text("$name ($code)\nStock Detail")
                fontSize(16f)
                fontWeightBold()
                color(0xFFFFFFFF)
                marginLeft(8f)
            }
        }

        View { attr { flex(1f) } }

        // AI 分析按钮
        View {
            attr {
                padding(top = 8f, left = 12f, bottom = 8f, right = 12f)
            }
            event {
                click {
                    ctx.triggerAIAnalysis()
                }
            }
            Text {
                attr {
                    text("🤖\nAI分析")
                    fontSize(12f)
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
                text("📋 基础信息\nBasic Info")
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
                text("$label\nLabel")
                fontSize(13f)
                color(0xFF666666)
                width(80f)
            }
        }

        Text {
            attr {
                text("$value\nValue")
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
                text("💹 实时行情\nReal-time Quote")
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
                    text("${realtime.price ?: "-"}\nPrice")
                    fontSize(28f)
                    fontWeightBold()
                    color(priceColor)
                }
            }

            Text {
                attr {
                    text("${realtime.changePercent?.let { String.format("%.2f%%", it) } ?: "-"}\nChange%")
                    fontSize(16f)
                    fontWeightBold()
                    color(priceColor)
                    marginLeft(8f)
                }
            }

            Text {
                attr {
                    text("${realtime.change?.let { String.format("%.2f", it) } ?: "-"}\nChange")
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
                text("$label\nLabel")
                fontSize(11f)
                color(0xFF999999)
            }
        }

        Text {
            attr {
                text("${value?.let { String.format("%.2f", it) } ?: "-"}$suffix\nValue")
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
    }
}

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
                text("📊 K线走势（近30日）\nK-Line Chart")
                fontSize(15f)
                fontWeightBold()
                color(0xFF333333)
                marginBottom(8f)
            }
        }

        if (klineData != null && klineData.isNotEmpty()) {
            // 图表占位符（实际项目应使用图表库渲染）
            chartPlaceholder(klineData)

            // 最新几条K线数据摘要
            klineSummary(klineData)
        } else {
            // 无数据提示
            View {
                attr {
                    padding(top = 20f, left = 0f, bottom = 20f, right = 0f)
                }
                Text {
                    attr {
                        text("暂无K线数据\nNo K-Line data available")
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
                text("📈 [K线图表区域]\n\n实际项目中应集成 MPAndroidChart 或其他图表库\n展示 ${klineData.size} 条K线数据")
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
                    text("最新: ${latest.tradeDate}\nLatest Date")
                    fontSize(12f)
                    color(0xFF666666)
                }
            }

            Text {
                attr {
                    text("收: ${latest.close}\nClose")
                    fontSize(12f)
                    fontWeightBold()
                    color(0xFF333333)
                    marginLeft(12f)
                }
            }

            Text {
                attr {
                    text("量: ${latest.volume.toInt()} 手\nVolume")
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
                    text("🤖 AI 智能解读\nAI Analysis")
                    fontSize(15f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }

            if (!ctx.isAnalyzing) {
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
                            text("🔄 刷新分析\nRefresh")
                            fontSize(11f)
                            color(0xFF666666)
                        }
                    }
                }
            }
        }

        if (ctx.isAnalyzing) {
            // 分析中状态
            analyzingView()
        } else if (analysis == null) {
            // 未分析状态
            notAnalyzedView(ctx)
        } else {
            // 显示分析卡片
            analysis.cards.forEach { card ->
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
            "trend_card" -> "📈"
            "signal_card" -> "📡"
            "risk_card" -> "⚠️"
            "suggestion_card" -> "💡"
            "summary_card" -> "📋"
            else -> "📌"
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
                text("⏳ AI 正在分析中...\nAnalyzing...")
                fontSize(14f)
                color(0xFF666666)
            }
        }

        Text {
            attr {
                text("请稍候，DeepSeek 正在为您生成专业分析报告\nPlease wait while DeepSeek generates analysis")
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
                text("🤔 尚未进行 AI 分析\nNot analyzed yet")
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
                    text("开始 AI 分析\nStart AI Analysis")
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFFFFFFFF)
                }
            }
        }

        Text {
            attr {
                text("AI 将为您分析趋势、信号、风险并给出操作建议\nAI will analyze trends, signals, risks and suggestions")
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
                text("⏳ 加载中...\nLoading...")
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
                text("❌ 加载失败\nLoad failed")
                fontSize(16f)
                color(0xFFE53935)
            }
        }

        Text {
            attr {
                text("未找到股票 ${ctx.stockCode} 的数据\nNo data found for stock ${ctx.stockCode}")
                fontSize(13f)
                color(0xFF999999)
                marginTop(8f)
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
                    text("重试\nRetry")
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
 * 股票详情聚合数据
 */
data class StockDetailData(
    val info: StockInfoData?,
    val realtime: RealtimeQuoteData?,
    val kline: List<KLineDataItem>?
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
