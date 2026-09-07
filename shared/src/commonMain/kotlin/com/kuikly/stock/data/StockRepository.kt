// 数据仓库层：统一调度本地数据库与网络接口，对页面屏蔽数据来源细节。

package com.kuikly.stock.data

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

    suspend fun chat(message: String): ChatResult {
        val result = chatImpl(message)
        val compare = try { CompareCards.maybeBuild(message) } catch (e: Throwable) { null }
        if (compare == null) return result
        val merged = (compare + (result.cards ?: emptyList())).ifEmpty { null }
        return result.copy(cards = merged)
    }

    private suspend fun chatImpl(message: String): ChatResult {
        if (!DataSourceManager.isOnline) {
            val mock = LocalDataService.mockChat(message)
            return mock.copy(
                text = "【AI 已关闭，以下为本地模板回答】\n\n" + mock.text,
                errorNotice = "AI 已关闭：回答基于本地数据"
            )
        }
        if (!AiRuntimeConfig.isConfigured()) {
            val mock = LocalDataService.mockChat(message)
            return mock.copy(
                text = "未配置 AI 服务，请在“我的 → API 配置”中填写密钥。\n\n以下为本地模板回答：\n\n" + mock.text,
                errorNotice = "请先配置 API Key"
            )
        }
        return try {
            val mentioned = StockDb.detectMentioned(message)
            withTimeout(95000) {
                DeepSeekApi.chat(message, mentioned) {
                    DeepSeekApi.buildChatContext(message, mentioned)
                }
            }
        } catch (e: TimeoutCancellationException) {
            ApiClient.recreate()
            val mock = LocalDataService.mockChat(message)
            mock.copy(
                text = " AI 响应超时（已重置连接，请重试）\n\n以下为本地模板回答，非真实 AI：\n\n" + mock.text,
                errorNotice = "AI 响应超时（已重置连接，请重试）"
            )
        } catch (e: Throwable) {
            val mock = LocalDataService.mockChat(message)
            val reason = friendlyAiError(e.message)
            mock.copy(
                text = " $reason\n以下为本地模板回答，非真实 AI：\n\n" + mock.text,
                errorNotice = reason
            )
        }
    }

    suspend fun analyzeStock(code: String): AIAnalysisData? {
        if (!DataSourceManager.isOnline || !AiRuntimeConfig.isConfigured()) {
            println("[Repo] analyzeStock OFFLINE fallback for " + code)
            return LocalDataService.mockAnalysis(code)
        }
        return try {
            val detail = StockDb.stockDetail(code) ?: return LocalDataService.mockAnalysis(code)
            println("[Repo] analyzeStock calling DeepSeek for " + code)
            val result = DeepSeekApi.analyzeStock(detail)
            println("[Repo] analyzeStock DONE cards=" + result.cards.size)
            result
        } catch (e: Throwable) {
            println("[Repo] analyzeStock FAILED: " + (e.message ?: e.toString()))
            LocalDataService.mockAnalysis(code)
        }
    }

    suspend fun loadMinute(code: String): List<MinutePoint>? {
        return try { StockDb.minute(code) } catch (e: Throwable) { null }
    }

    suspend fun loadOrderBook(code: String): OrderBookData? {
        return try { StockDb.orderBook(code) } catch (e: Throwable) { null }
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
        val dataLine = if (dbOk) " 本地 SQLite：已就绪" else " 本地 SQLite：未就绪（检查 assets/stock.db）"
        val active = AiRuntimeConfig.activeProfile()
        val aiLine = if (!DataSourceManager.isOnline) "AI：已关闭（当前为离线模式）"
        else if (!AiRuntimeConfig.isConfigured()) "AI：${active.name} / ${active.model}（未配置密钥）"
        else "AI：${active.name} / ${active.model}（密钥已配置）"
        return "$dataLine\n$aiLine\n行情来自本地 SQLite，AI 使用用户选择的服务商"
    }
}
