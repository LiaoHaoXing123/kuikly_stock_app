package com.kuikly.stock.network

import com.kuikly.stock.data.*
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * 后端 API 服务层
 *
 * 统一封装三类请求：股票列表、个股详情、AI 分析与 AI 问答。
 * 后端统一返回 { success, message, data }，本类负责拆包并映射到页面使用的数据结构。
 *
 * 注意：所有方法都是 suspend 函数，必须在 Kuikly 的 lifecycleScope.launch { } 内调用。
 */
object ApiService {

    /**
     * 拆包后端统一响应 { success, message, data } 并反序列化 data。
     * 用原始文本 + 显式 serializer 解析，避免 Ktor 泛型包装 ApiResponse<T> 的序列化坑。
     */
    private suspend inline fun <reified T> unwrap(
        request: suspend () -> HttpResponse
    ): T {
        val resp = request()
        val root = ApiClient.json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!success) {
            val msg = root["message"]?.jsonPrimitive?.contentOrNull ?: "请求失败"
            throw Exception(msg)
        }
        val data = root["data"] ?: throw Exception("响应缺少 data 字段")
        return ApiClient.json.decodeFromJsonElement(serializer<T>(), data)
    }

    /**
     * 获取股票列表
     * GET /api/v1/stocks?page=&size=&keyword=&sort=&order=
     * 后端 data 为分页对象 { total, page, page_size, items }，需取 items
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
        val paginated = unwrap<PaginatedResponse<StockInfo>> {
            ApiClient.client.get(url)
        }
        return paginated.items.map { it.toStockListItem() }
    }

    /**
     * 获取个股详情
     * GET /api/v1/stocks/{code}/detail
     */
    suspend fun getStockDetail(code: String): StockDetailVO {
        val url = "${ApiEndpoints.BASE_URL}${ApiEndpoints.Stocks.DETAIL}".replace("{code}", code)
        val data = unwrap<StockDetail> {
            ApiClient.client.get(url)
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
            }
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
            }
        }
        return data.toVO()
    }

    /**
     * AI 服务状态（连接检测）
     * GET /api/v1/ai/status
     */
    suspend fun getAiStatus(): AiStatusResponse {
        // 带 probe=app 标记，后端日志据此区分是 App 还是浏览器在请求
        val url = "${ApiEndpoints.BASE_URL}${ApiEndpoints.AI.STATUS}?probe=app"
        return unwrap<AiStatusResponse> {
            ApiClient.client.get(url)
        }
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
    val cards: List<Map<String, Any?>>
)

fun AIAnalysisResponse.toVO(): AIAnalysisVO = AIAnalysisVO(
    code = code,
    name = name,
    analysis = analysis,
    cards = cards.map { it.mapValues { (_, v) -> v.toAny() } }
)

/** 页面使用的 AI 问答回复 */
data class ChatReplyVO(
    val text: String,
    val cards: List<Map<String, Any?>>?,
    val suggestions: List<String>?
)

fun ChatResponse.toVO(): ChatReplyVO = ChatReplyVO(
    text = reply.text,
    cards = reply.cards?.map { it.mapValues { (_, v) -> v.toAny() } },
    suggestions = reply.suggestions
)

/**
 * kotlinx JsonElement -> 页面可用的 Any?
 * 字符串保持字符串（卡片显示用），数字转 Double，数组/对象递归转换。
 */
internal fun JsonElement.toAny(): Any? = when (this) {
    // JsonNull 是 JsonPrimitive 的子类，必须先判断
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toDoubleOrNull() ?: content
    }
    is JsonArray -> map { it.toAny() }
    is JsonObject -> mapValues { it.value.toAny() }
}
