package com.kuikly.stock.data

import com.kuikly.stock.network.AIAnalysisVO
import com.kuikly.stock.network.ApiClient
import com.kuikly.stock.network.ApiEndpoints
import com.kuikly.stock.network.ApiService
import com.kuikly.stock.network.ChatReplyVO
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.network.DeepSeekConfig
import com.kuikly.stock.network.StockDetailVO
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

/**
 * 统一数据入口：按 [DataSourceManager.mode] 路由到
 * - 离线：[LocalDataService]（assets 内嵌 JSON，默认，无需后端/同网）
 * - 在线：[ApiService]（局域网后端，不可达时自动回退离线）
 *
 * 页面只依赖本仓库，不直接接触数据来源细节。
 */
object StockRepository {

    /**
     * 股票列表（含关键词搜索与排序，客户端分页用全量匹配集）
     * @param sort 排序列（change_percent / volume / name），null 为默认按代码
     * @param order asc / desc，null 默认 desc
     */
    suspend fun loadStockList(
        keyword: String?,
        sort: String? = null,
        order: String? = null
    ): List<StockListItem> {
        return try {
            StockDb.listStocks(keyword, sort, order, 1, 5000).items
        } catch (e: Throwable) {
            // SQLite 不可用时回退旧离线 JSON（assets 内的真实数据），并按排序参数在客户端排序
            val k = keyword ?: ""
            val base = if (k.isBlank()) LocalDataService.loadStockList()
            else LocalDataService.searchStocks(k)
            sortLocal(base, sort, order)
        }
    }

    /**
     * 股票列表 + 匹配总数（页面标题显示"共 N 只"用，一次查询拿全）
     * @return Pair(total, items)
     */
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

    /** 回退分支的客户端排序（null 值永远排最后，与 SQL 层 ORDER BY (col IS NULL) 一致） */
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

    /** 个股详情（基础信息 + 实时行情 + K线 + 最新技术指标，全部来自本地 SQLite） */
    suspend fun loadStockDetail(code: String): StockDetailData? {
        return try {
            StockDb.stockDetail(code) ?: LocalDataService.loadStockDetail(code)
        } catch (e: Throwable) {
            LocalDataService.loadStockDetail(code)
        }
    }

    /**
     * AI 问答：优先真实 AI（后端 DeepSeek，基于数据文件夹作答），
     * 后端不可达时回退内置模板回答，保证 App 不中断。
     *
     * 【重要】回退时会在回复文本开头明确标注"⚠️ 连接失败，以下为模板回答"，
     * 避免用户误以为是真实 AI 生成的内容。
     */
    suspend fun chat(message: String): ChatResult {
        // 离线模式：不调用后端，直接本地回答，并在开头醒目标识（区别于在线真实 AI）
        if (!DataSourceManager.isOnline) {
            val mock = LocalDataService.mockChat(message)
            return mock.copy(
                text = "【AI 已关闭，以下为本地模板回答】\n\n" + mock.text,
                errorNotice = "AI 已关闭：回答基于本地数据"
            )
        }
        if (!DeepSeekConfig.enabled) {
            val mock = LocalDataService.mockChat(message)
            return mock.copy(
                text = "⚠️ 未配置 DeepSeek API Key（见 network/DeepSeekApi.kt 中 DeepSeekConfig），以下为本地模板回答：\n\n" + mock.text,
                errorNotice = "未配置 DeepSeek API Key"
            )
        }
        return try {
            // 方案 B：数据上下文来自本地 SQLite，AI 直连 DeepSeek
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
                text = "⚠️ AI 响应超时（已重置连接，请重试）\n\n以下为本地模板回答，非真实 AI：\n\n" + mock.text,
                errorNotice = "AI 响应超时（已重置连接，请重试）"
            )
        } catch (e: Throwable) {
            val mock = LocalDataService.mockChat(message)
            val reason = friendlyAiError(e.message)
            mock.copy(
                text = "⚠️ $reason\n以下为本地模板回答，非真实 AI：\n\n" + mock.text,
                errorNotice = reason
            )
        }
    }

    /** AI 个股分析：真实 AI（DeepSeek 直连），不可达回退本地模拟分析 */
    suspend fun analyzeStock(code: String): AIAnalysisData? {
        if (!DataSourceManager.isOnline || !DeepSeekConfig.enabled) {
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

    /** 分时1分钟数据：本地 SQLite（离线即用） */
    suspend fun loadMinute(code: String): List<MinutePoint>? {
        return try { StockDb.minute(code) } catch (e: Throwable) { null }
    }

    /** 五档盘口：本地 SQLite（仅 11 只热门股有数据） */
    suspend fun loadOrderBook(code: String): OrderBookData? {
        return try { StockDb.orderBook(code) } catch (e: Throwable) { null }
    }

    /** 数据来源标注（data_source 表：tableName -> source），老库无此表时返回空列表 */
    suspend fun dataSources(): List<Pair<String, String>> {
        return try { StockDb.dataSources() } catch (e: Throwable) { emptyList() }
    }

    // ==================== 离线分支 ====================

    private fun offlineList(keyword: String?): List<StockListItem> {
        val k = keyword ?: ""
        return if (k.isBlank()) LocalDataService.loadStockList()
        else LocalDataService.searchStocks(k)
    }

    /** 回退回答：气泡文本保持原样，只带简短错误提示 */
    private fun ChatResult.withErrorNotice(reason: String): ChatResult = copy(errorNotice = reason)

    /** 把原始异常映射成一句简短、可读的提示（太长反而挤） */
    private fun friendlyAiError(raw: String?): String {
        val msg = raw ?: "未知错误"
        return when {
            msg.contains("无法连接 AI 服务") -> "电脑端无法连接 DeepSeek（检查电脑网络/代理）"
            msg.contains("超时") || msg.contains("timeout", ignoreCase = true) ->
                "AI 响应超时，请稍后重试"
            msg.contains("连接") || msg.contains("connect", ignoreCase = true) ->
                "无法连接后端（手机与电脑是否同网？防火墙是否放行 8000？）"
            msg.contains("HTTP") -> "AI 接口返回错误"
            msg.contains("模型") -> "AI 模型配置有误"
            msg.contains("Event loop") -> "AI 服务内部错误，请重试"
            else -> if (msg.length > 30) msg.substring(0, 30) + "…" else msg
        }
    }

    /**
     * 方案 B 连接检测（开发者面板用）：报告本地 SQLite + DeepSeek 配置
     */
    suspend fun checkAiService(): String {
        val dbOk = try { StockDb.isAvailable() } catch (e: Throwable) { false }
        val dataLine = if (dbOk) "✅ 本地 SQLite：已就绪" else "❌ 本地 SQLite：未就绪（检查 assets/stock.db）"
        val aiLine = if (!DataSourceManager.isOnline) "AI：已关闭（开发者选项处于离线）"
            else if (!DeepSeekConfig.enabled) "⚠️ DeepSeek：未配置 API Key（见 network/DeepSeekApi.kt）"
            else "✅ DeepSeek：${DeepSeekConfig.MODEL}（key 已配置）"
        return "$dataLine\n$aiLine\n方案 B：数据本地 SQLite，AI 直连 DeepSeek"
    }

    // ==================== 在线 VO -> 页面数据类映射（旧后端路径，保留兼容） ====================

    private fun StockDetailVO.toStockDetailData(): StockDetailData = StockDetailData(
        info = StockInfoData(info.code, info.name, info.industry, info.plate, info.listDate),
        realtime = realtime?.let {
            RealtimeQuoteData(
                code = it.code, name = it.name, price = it.price, change = it.change,
                changePercent = it.changePercent, openPrice = it.openPrice, preClose = it.preClose,
                high = it.high, low = it.low, volume = it.volume, amount = it.amount,
                peTtm = it.peTtm, pb = it.pb
            )
        },
        kline = kline.map {
            KLineDataItem(
                code = it.code, tradeDate = it.tradeDate, open = it.open, close = it.close,
                high = it.high, low = it.low, volume = it.volume, amount = it.amount
            )
        },
        indicator = indicator?.let {
            IndicatorData(
                tradeDate = it.tradeDate,
                ma5 = it.ma5, ma10 = it.ma10, ma20 = it.ma20,
                dif = it.dif, dea = it.dea, macd = it.macd,
                rsi6 = it.rsi6, kdjK = it.kdjK, kdjD = it.kdjD, kdjJ = it.kdjJ
            )
        }
    )

    private fun ChatReplyVO.toChatResult(): ChatResult = ChatResult(
        text = text,
        cards = cards,
        suggestions = suggestions
    )

    private fun AIAnalysisVO.toAIAnalysisData(): AIAnalysisData = AIAnalysisData(
        code = code,
        name = name,
        analysis = analysis,
        cards = cards
    )
}
