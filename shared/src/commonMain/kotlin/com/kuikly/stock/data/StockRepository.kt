package com.kuikly.stock.data

import com.kuikly.stock.ai.chat.*
import kotlinx.coroutines.CancellationException
import com.kuikly.stock.network.ApiClient
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.ai.config.AiRuntimeConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.kuikly.stock.pages.AIAnalysisData
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.KLineDataItem
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockInfoData
import com.kuikly.stock.pages.StockListItem

object StockRepository {

    suspend fun loadStockList(
        keyword: String?,
        sort: String? = null,
        order: String? = null
    ): List<StockListItem> {
        return try {
            StockDb.listStocks(keyword, sort, order, 1, 5000).items
        } catch (e: Throwable) {
            val k = keyword ?: ""
            val base = if (k.isBlank()) LocalDataService.loadStockList()
            else LocalDataService.searchStocks(k)
            sortLocal(base, sort, order)
        }
    }

    suspend fun loadStockListWithTotal(
        keyword: String?,
        sort: String? = null,
        order: String? = null
    ): Pair<Int, List<StockListItem>> {
        return try {
            val page = StockDb.listStocks(keyword, sort, order, 1, 5000)
            page.total to page.items
        } catch (e: Throwable) {
            val k = keyword ?: ""
            val base = if (k.isBlank()) LocalDataService.loadStockList()
            else LocalDataService.searchStocks(k)
            base.size to sortLocal(base, sort, order)
        }
    }

    private fun sortLocal(list: List<StockListItem>, sort: String?, order: String?): List<StockListItem> {
        if (sort == null) return list
        val asc = order?.equals("asc", true) == true
        return when (sort) {
            "change_percent" -> if (asc) list.sortedBy { it.changePercent ?: Double.MAX_VALUE }
            else list.sortedByDescending { it.changePercent ?: Double.MIN_VALUE }
            "volume" -> if (asc) list.sortedBy { it.volume ?: Double.MAX_VALUE }
            else list.sortedByDescending { it.volume ?: Double.MIN_VALUE }
            else -> list
        }
    }

    suspend fun loadStockDetail(code: String): StockDetailData? {
        return try {
            StockDb.stockDetail(code) ?: LocalDataService.loadStockDetail(code)
        } catch (e: Throwable) {
            LocalDataService.loadStockDetail(code)
        }
    }

    suspend fun chat(
        message: String,
        history: List<Pair<String, String>> = emptyList(),
        onText: suspend (String) -> Unit = {},
        onStage: suspend (String) -> Unit = {},
    ): ChatResult {
        val context = boundedHistory(history)
        onStage("正在识别股票/指数与追问…")
        val mentioned = resolveFocus(message, context) { input ->
            val aliases = mapOf("茅台" to "贵州茅台", "宁德" to "宁德时代")
            val expanded = input + aliases.filterKeys { it in input }.values.joinToString(" ", prefix = " ")
            runCatching { StockDb.detectMentioned(expanded) }.getOrDefault(emptyList())
        }
        val mentionedIndices = resolveFocus(message, context) { input ->
            runCatching { StockDb.detectMentionedIndices(input) }.getOrDefault(emptyList())
        }
        val allMentioned = (mentioned + mentionedIndices).take(4)
        val result = chatImpl(message, allMentioned, context, onText, onStage)
        val compare = runCatching { CompareCards.maybeBuild(message) }.getOrNull().orEmpty()

        val requestedCharts = result.cards.orEmpty().filter { it["type"] == "chart_card" }
        val wantsChart = listOf("走势", "趋势", "图", "K线", "k线", "成交量").any { it in message }
        val codes = (requestedCharts.mapNotNull { it["code"] as? String } +
            if (wantsChart || requestedCharts.isNotEmpty()) allMentioned.map { it.code } else emptyList()).distinct().take(2)
        val charts = codes.mapNotNull { code ->
            val preferIndex = allMentioned.firstOrNull { it.code == code }?.isIndex == true
            val (detail, isIndex) = loadChartDetail(code, preferIndex) ?: return@mapNotNull null
            val bars = detail.kline.orEmpty().takeLast(60)
            val volume = "成交量" in message || requestedCharts.any { it["code"] == code && it["chart_type"] == "bar" }
            val chart = buildLocalChart(code, detail.info?.name ?: code, bars, volume) ?: return@mapNotNull null
            if (isIndex) chart + ("unit" to if (volume) "股" else "点") else chart
        }
        val notice = if ((wantsChart || requestedCharts.isNotEmpty()) && charts.isEmpty()) "暂无可用的本地 K 线，未生成走势图" else null
        return result.copy(
            cards = (compare + result.cards.orEmpty().filter { it["type"] != "chart_card" } + charts).ifEmpty { null },
            errorNotice = listOfNotNull(result.errorNotice, notice).joinToString("；").ifBlank { null },
        )
    }

    private fun loadChartDetail(code: String, preferIndex: Boolean): Pair<StockDetailData, Boolean>? {
        return if (preferIndex) {
            runCatching { StockDb.indexDetail(code) }.getOrNull()?.let { it to true }
                ?: runCatching { StockDb.stockDetail(code) }.getOrNull()?.let { it to false }
        } else {
            runCatching { StockDb.stockDetail(code) }.getOrNull()?.let { it to false }
                ?: runCatching { StockDb.indexDetail(code) }.getOrNull()?.let { it to true }
        }
    }

    private suspend fun chatImpl(
        message: String,
        mentioned: List<StockListItem>,
        history: List<Pair<String, String>>,
        onText: suspend (String) -> Unit,
        onStage: suspend (String) -> Unit,
    ): ChatResult {
        if (!DataSourceManager.isOnline || !AiRuntimeConfig.isConfigured()) {
            val reason = if (!DataSourceManager.isOnline) "AI 已关闭" else "请先在我的 → API 配置中填写密钥"
            val fallback = LocalDataService.mockChat(message)
            return fallback.copy(
                text = "【本地模板回答，非 AI 生成】\n" + fallback.text,
                errorNotice = reason,
                failed = true,
            )
        }
        return try {
            withTimeout(95000) {
                DeepSeekApi.chat(message, mentioned, history, onText, onStage) {
                    DeepSeekApi.buildChatContext(message, mentioned)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("AI 响应超时，请重试", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatProtocolException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(friendlyAiError(e.message), e)
        }
    }

    suspend fun analyzeStock(code: String): AIAnalysisData = analyzeDetail(code, false)

    private suspend fun analyzeDetail(code: String, index: Boolean): AIAnalysisData {
        val detail = if (index) StockDb.indexDetail(code) else StockDb.stockDetail(code)
        if (detail == null) throw IllegalStateException("没有可用行情，请先刷新数据")
        val dataDate = detail.kline?.lastOrNull()?.tradeDate ?: detail.indicator?.tradeDate.orEmpty()
        if (!DataSourceManager.isOnline || !AiRuntimeConfig.isConfigured()) {
            val template = if (index) LocalDataService.mockIndexAnalysis(detail) else LocalDataService.mockAnalysis(detail)
            return template.copy(source = "本地模板 · 非 AI 生成", generatedAt = nowMillis(), dataDate = dataDate)
        }
        val config = AiRuntimeConfig.current()
        val result = try {
            withTimeout(95000) {
                if (index) DeepSeekApi.analyzeIndex(detail) else DeepSeekApi.analyzeStock(detail)
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("AI 响应超时，请重试", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(friendlyAiError(e.message), e)
        }
        if (result.cards.isEmpty()) throw IllegalStateException("AI 返回的分析不完整，请重试")
        return result.copy(source = "${config.providerName} · ${config.model}", generatedAt = nowMillis(), dataDate = dataDate)
    }
    suspend fun loadIndexDetail(code: String): StockDetailData? {
        return try {
            StockDb.indexDetail(code)
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun analyzeIndex(code: String): AIAnalysisData = analyzeDetail(code, true)

    suspend fun loadMinute(code: String): List<MinutePoint>? {
        return StockDb.minute(code)
    }

    suspend fun loadOrderBook(code: String): OrderBookData? {
        return StockDb.orderBook(code)
    }

    suspend fun dataSources(): List<Pair<String, String>> {
        return try { StockDb.dataSources() } catch (e: Throwable) { emptyList() }
    }

    private fun offlineList(keyword: String?): List<StockListItem> {
        val k = keyword ?: ""
        return if (k.isBlank()) LocalDataService.loadStockList()
        else LocalDataService.searchStocks(k)
    }

    private fun ChatResult.withErrorNotice(reason: String): ChatResult = copy(errorNotice = reason)

    private fun friendlyAiError(raw: String?): String {
        val msg = raw ?: "未知错误"
        return when {
            msg.contains("无法连接 AI 服务") -> "电脑端无法连接 DeepSeek（检查电脑网络/代理）"
            msg.contains("超时") || msg.contains("timeout", ignoreCase = true) ->
            "AI 响应超时，请稍后重试"
            msg.contains("连接") || msg.contains("connect", ignoreCase = true) ->
            "无法连接 DeepSeek（请检查网络，在线模式需可直连）"
            msg.contains("HTTP") -> "AI 接口返回错误"
            msg.contains("模型") -> "AI 模型配置有误"
            msg.contains("Event loop") -> "AI 服务内部错误，请重试"
            else -> if (msg.length > 30) msg.substring(0, 30) + "…" else msg
        }
    }

    suspend fun checkAiService(): String {
        val dbOk = try { StockDb.isAvailable() } catch (e: Throwable) { false }
        val dataLine = if (dbOk) " 本地行情库：已就绪" else " 本地行情库：未就绪"
        val active = AiRuntimeConfig.activeProfile()
        val aiLine = if (!DataSourceManager.isOnline) "AI：已关闭（当前为离线模式）"
        else if (!AiRuntimeConfig.isConfigured()) "AI：${active.name} / ${active.model}（未配置密钥）"
        else "AI：${active.name} / ${active.model}（密钥已配置）"
        return "$dataLine\n$aiLine\n行情来自本地行情库，AI 使用用户选择的服务商"
    }
}
