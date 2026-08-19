package com.kuikly.stock.network

import com.kuikly.stock.data.*
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 后端 API 服务层
 *
 * 统一封装三类请求：股票列表、个股详情、AI 分析与 AI 问答。
 * 后端统一返回 { success, message, data }，本类负责拆包并映射到页面使用的数据结构。
 *
 * 注意：所有方法都是 suspend 函数，必须在 Kuikly 的 lifecycleScope.launch { } 内调用。
 */
object ApiService {

    /** 拆包后端统一响应，失败时抛出异常 */
    private suspend inline fun <reified T> unwrap(
        request: suspend () -> ApiResponse<T>
    ): T {
        val resp = request()
        if (!resp.success || resp.data == null) {
            throw Exception(resp.message.ifEmpty { "请求失败" })
        }
        return resp.data
    }

    /**
     * 获取股票列表
     * GET /api/v1/stocks?page=&size=&keyword=&sort=&order=
     */
    suspend fun getStockList(
        page: Int = 1,
        size: Int = 20,
        keyword: String? = null,
        sort: String? = null,
        order: String? = null
    ): List<com.kuikly.stock.pages.StockListItem> {
        val url = buildString {
            append("${ApiEndpoints.BASE_URL}${ApiEndpoints.Stocks.LIST}?page=$page&size=$size")
            if (!keyword.isNullOrBlank()) append("&keyword=${encode(keyword)}")
            if (!sort.isNullOrBlank()) append("&sort=$sort")
            if (!order.isNullOrBlank()) append("&order=$order")
        }
        val data = unwrap<List<StockInfo>> {
            ApiClient.client.get(url).body<ApiResponse<List<StockInfo>>>()
        }
        return data.map { it.toStockListItem() }
    }

    /**
     * 获取个股详情
     * GET /api/v1/stocks/{code}/detail
     */
    suspend fun getStockDetail(code: String): StockDetailVO {
        val url = "${ApiEndpoints.BASE_URL}${ApiEndpoints.Stocks.DETAIL}".replace("{code}", code)
        val data = unwrap<StockDetail> {
            ApiClient.client.get(url).body<ApiResponse<StockDetail>>()
        }
        return data.toVO(code)
    }

    /**
     * AI 个股分析
     * POST /api/v1/ai/analyze/{code}?analysis_type=full
     */
    suspend fun analyzeStock(code: String): AIAnalysisVO {
        val url = "${ApiEndpoints.BASE_URL}${ApiEndpoints.AI.ANALYZE}".replace("{code}", code) +
            "?analysis_type=full"
        val data = unwrap<AIAnalysisResponse> {
            ApiClient.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("analysis_type", "full") })
            }.body<ApiResponse<AIAnalysisResponse>>()
        }
        return data.toVO()
    }

    /**
     * AI 问答
     * POST /api/v1/ai/chat
     */
    suspend fun chat(message: String, sessionId: String? = null): ChatReplyVO {
        val url = "${ApiEndpoints.BASE_URL}${ApiEndpoints.AI.CHAT}"
        val bodyJson: JsonObject = buildJsonObject {
            put("message", message)
            if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
        }
        val data = unwrap<ChatResponse> {
            ApiClient.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }.body<ApiResponse<ChatResponse>>()
        }
        return data.toVO()
    }

    /** 简单 URL 编码（避免引入额外依赖） */
    private fun encode(s: String): String {
        return s.map { c ->
            if (c.isLetterOrDigit() || "-_.~".contains(c)) c.toString()
            else "%${c.code.toString(16).uppercase().padStart(2, '0')}"
        }.joinToString("")
    }
}

// ==================== 后端模型 -> 页面视图模型 映射 ====================

/** 页面使用的股票列表项（复用 StockListPage 中定义的 StockListItem） */
fun StockInfo.toStockListItem(): com.kuikly.stock.pages.StockListItem = com.kuikly.stock.pages.StockListItem(
    code = code,
    name = name,
    price = null,
    changePercent = null
)

/** 页面使用的个股详情聚合 */
data class StockDetailVO(
    val info: StockInfoDataVO,
    val realtime: RealtimeQuoteDataVO?,
    val kline: List<KLineDataItemVO>
)

data class StockInfoDataVO(
    val code: String,
    val name: String?,
    val industry: String?,
    val plate: String?,
    val listDate: String?
)

data class RealtimeQuoteDataVO(
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

data class KLineDataItemVO(
    val code: String,
    val tradeDate: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double?
)

fun StockDetail.toVO(fallbackCode: String): StockDetailVO = StockDetailVO(
    info = (info ?: StockInfo(code = fallbackCode)).let {
        StockInfoDataVO(it.code, it.name, it.industry, it.plate, it.listDate)
    },
    realtime = realtime?.let {
        RealtimeQuoteDataVO(
            code = it.code, name = it.name, price = it.price, change = it.change,
            changePercent = it.changePercent, openPrice = it.open, preClose = it.preClose,
            high = it.high, low = it.low, volume = it.volume, amount = it.amount,
            peTtm = it.peTtm, pb = it.pb
        )
    },
    kline = (kline ?: emptyList()).map {
        KLineDataItemVO(
            code = it.code, tradeDate = it.tradeDate, open = it.open, close = it.close,
            high = it.high, low = it.low, volume = it.volume, amount = it.amount
        )
    }
)

/** 页面使用的 AI 分析聚合 */
data class AIAnalysisVO(
    val code: String,
    val name: String?,
    val analysis: Map<String, String>,
    val cards: List<Map<String, String>>
)

fun AIAnalysisResponse.toVO(): AIAnalysisVO = AIAnalysisVO(
    code = code,
    name = name,
    analysis = analysis,
    cards = cards
)

/** 页面使用的 AI 问答回复 */
data class ChatReplyVO(
    val text: String,
    val cards: List<Map<String, String>>?,
    val suggestions: List<String>?
)

fun ChatResponse.toVO(): ChatReplyVO = ChatReplyVO(
    text = reply.text,
    cards = reply.cards,
    suggestions = reply.suggestions
)
