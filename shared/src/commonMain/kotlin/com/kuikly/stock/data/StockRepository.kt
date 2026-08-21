package com.kuikly.stock.data

import com.kuikly.stock.network.AIAnalysisVO
import com.kuikly.stock.network.ApiClient
import com.kuikly.stock.network.ApiEndpoints
import com.kuikly.stock.network.ApiService
import com.kuikly.stock.network.ChatReplyVO
import com.kuikly.stock.network.StockDetailVO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.kuikly.stock.pages.AIAnalysisData
import com.kuikly.stock.pages.KLineDataItem
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

    /** 股票列表（含关键词搜索） */
    suspend fun loadStockList(keyword: String?): List<StockListItem> {
        return if (DataSourceManager.isOnline) {
            try {
                ApiService.getStockList(keyword = keyword)
            } catch (e: Throwable) {
                offlineList(keyword)
            }
        } else {
            offlineList(keyword)
        }
    }

    /** 个股详情（基础信息 + 实时行情 + K线） */
    suspend fun loadStockDetail(code: String): StockDetailData? {
        return if (DataSourceManager.isOnline) {
            try {
                ApiService.getStockDetail(code).toStockDetailData()
            } catch (e: Throwable) {
                LocalDataService.loadStockDetail(code)
            }
        } else {
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
                text = "【当前处于离线模式，行情数据不可用】\n\n" + mock.text,
                errorNotice = "离线模式：未调用后端，回答基于本地数据"
            )
        }
        return try {
            // 95 秒硬上限：即使底层连接异常卡死，也不会让用户无限等待
            withTimeout(95000) { ApiService.chat(message) }.toChatResult()
        } catch (e: TimeoutCancellationException) {
            ApiClient.recreate()
            val mock = LocalDataService.mockChat(message)
            mock.copy(
                text = "⚠️ AI 响应超时（已重置连接，请重试）\n\n以下为本地模板回答，非真实 AI：\n\n" + mock.text,
                errorNotice = "AI 响应超时（已重置连接，请重试）"
            )
        } catch (e: Throwable) {
            // 回退内置回答，并在文本里明确标注，避免误导用户
            val mock = LocalDataService.mockChat(message)
            val reason = friendlyAiError(e.message)
            mock.copy(
                text = "⚠️ $reason\n以下为本地模板回答，非真实 AI：\n\n" + mock.text,
                errorNotice = reason
            )
        }
    }

    /** AI 个股分析：优先真实 AI（后端），不可达回退本地模拟分析 */
    suspend fun analyzeStock(code: String): AIAnalysisData? {
        return try {
            ApiService.analyzeStock(code).toAIAnalysisData()
        } catch (e: Throwable) {
            LocalDataService.mockAnalysis(code)
        }
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
     * 检测 AI 服务连接（开发者面板"连接检测"用）
     * 返回一段可直接展示的状态文本
     */
    /**
     * 检测 AI 服务连接（开发者面板"连接检测"用）
     * 返回一段可直接展示的状态文本。
     * 失败自动重建连接池并重试一次：OkHttp 连接池可能缓存了被后端关闭的 keep-alive
     * 死连接（如后端重启/实例切换），复用会抛 "unexpected end of stream"，重试即可成功。
     */
    suspend fun checkAiService(): String {
        var lastError: String? = null
        repeat(2) { attempt ->
            try {
                // 15 秒硬上限：状态接口本地秒回，卡住说明连接层异常，不再无限等待
                val s = withTimeout(15000) { ApiService.getAiStatus() }
                val result = "✅ 后端可达" + "\n" +
                    "地址：" + ApiEndpoints.BASE_URL + "\n" +
                    "AI 模型：" + s.llm.model + "\n" +
                    "数据源：" + s.dataMode + "（" + s.localData.stocks + " 只 / " + s.localData.klineCodes + " 只K线）\n" +
                    "数据库：" + s.database
                println("[AiCheck] OK: " + result.replace("\n", " | "))
                return result
            } catch (e: TimeoutCancellationException) {
                // 连接卡死：重置 HTTP 客户端（清掉异常连接池），重试一次
                ApiClient.recreate()
                lastError = "检测超时（已重置连接）"
                println("[AiCheck] TIMEOUT attempt=$attempt: ${e.message}")
            } catch (e: Throwable) {
                // 典型：复用被后端关闭的 keep-alive 连接 → "unexpected end of stream"，重置后重试
                ApiClient.recreate()
                lastError = (e.message ?: "未知错误").let {
                    if (it.length > 60) it.substring(0, 60) + "…" else it
                }
                println("[AiCheck] ERROR attempt=$attempt: $lastError")
            }
        }
        return "❌ 无法连接后端" + "\n" +
            "地址：" + ApiEndpoints.BASE_URL + "\n" +
            "原因：" + (lastError ?: "未知错误") + "\n" +
            "排查：①后端是否启动 ②是否同一网络 ③防火墙 8000"
    }

    // ==================== 在线 VO -> 页面数据类映射 ====================

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
