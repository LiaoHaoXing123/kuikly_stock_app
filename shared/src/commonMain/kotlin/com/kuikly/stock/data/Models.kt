package com.kuikly.stock.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 通用 API 响应（后端统一包裹：{ success, message, data }）
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean = true,
    val message: String = "ok",
    val data: T? = null
)

/**
 * 分页响应（data 内）
 */
@Serializable
data class PaginatedResponse<T>(
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    val items: List<T> = emptyList()
)

/**
 * 股票列表项（对齐后端 snake_case 字段）
 * price/change_percent 来自后端 JOIN stock_realtime 的列表查询
 */
@Serializable
data class StockInfo(
    val code: String,
    val name: String? = null,
    val industry: String? = null,
    val plate: String? = null,
    @SerialName("list_date") val listDate: String? = null,
    val price: Double? = null,
    @SerialName("change_percent") val changePercent: Double? = null
)

/**
 * 实时行情快照（对齐后端 snake_case 字段）
 */
@Serializable
data class RealtimeQuote(
    val code: String,
    val name: String? = null,
    val price: Double? = null,
    val change: Double? = null,
    @SerialName("change_percent") val changePercent: Double? = null,
    val open: Double? = null,
    @SerialName("pre_close") val preClose: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val volume: Double? = null,
    val amount: Double? = null,
    @SerialName("turnover_rate") val turnoverRate: Double? = null,
    @SerialName("volume_ratio") val volumeRatio: Double? = null,
    @SerialName("pe_ttm") val peTtm: Double? = null,
    val pb: Double? = null,
    @SerialName("total_market_cap") val totalMarketCap: Double? = null,
    @SerialName("circulate_market_cap") val circulateMarketCap: Double? = null,
    @SerialName("update_time") val updateTime: String? = null
)

/**
 * K线数据（对齐后端 snake_case 字段）
 */
@Serializable
data class KLineData(
    val code: String,
    @SerialName("trade_date") val tradeDate: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double? = null
)

/**
 * 技术指标（stock_indicator 表）
 */
@Serializable
data class TechnicalIndicator(
    val code: String,
    @SerialName("trade_date") val tradeDate: String,
    val ma5: Double? = null,
    val ma10: Double? = null,
    val ma20: Double? = null,
    val dif: Double? = null,
    val dea: Double? = null,
    val macd: Double? = null,
    val rsi6: Double? = null,
    @SerialName("kdj_k") val kdjK: Double? = null,
    @SerialName("kdj_d") val kdjD: Double? = null,
    @SerialName("kdj_j") val kdjJ: Double? = null
)

/**
 * 分时1分钟数据（stock_minute 表）
 */
@Serializable
data class MinuteData(
    val code: String,
    @SerialName("trade_date") val tradeDate: String,
    val time: String,
    val price: Double,
    @SerialName("avg_price") val avgPrice: Double? = null,
    val volume: Double? = null
)

/**
 * 五档盘口（stock_order_book 表）
 */
@Serializable
data class OrderBook(
    val code: String,
    @SerialName("update_time") val updateTime: String? = null,
    @SerialName("bid1_price") val bid1Price: Double? = null,
    @SerialName("bid1_vol") val bid1Vol: Double? = null,
    @SerialName("bid2_price") val bid2Price: Double? = null,
    @SerialName("bid2_vol") val bid2Vol: Double? = null,
    @SerialName("bid3_price") val bid3Price: Double? = null,
    @SerialName("bid3_vol") val bid3Vol: Double? = null,
    @SerialName("bid4_price") val bid4Price: Double? = null,
    @SerialName("bid4_vol") val bid4Vol: Double? = null,
    @SerialName("bid5_price") val bid5Price: Double? = null,
    @SerialName("bid5_vol") val bid5Vol: Double? = null,
    @SerialName("ask1_price") val ask1Price: Double? = null,
    @SerialName("ask1_vol") val ask1Vol: Double? = null,
    @SerialName("ask2_price") val ask2Price: Double? = null,
    @SerialName("ask2_vol") val ask2Vol: Double? = null,
    @SerialName("ask3_price") val ask3Price: Double? = null,
    @SerialName("ask3_vol") val ask3Vol: Double? = null,
    @SerialName("ask4_price") val ask4Price: Double? = null,
    @SerialName("ask4_vol") val ask4Vol: Double? = null,
    @SerialName("ask5_price") val ask5Price: Double? = null,
    @SerialName("ask5_vol") val ask5Vol: Double? = null,
    @SerialName("commission_ratio") val commissionRatio: Double? = null
)

/**
 * 个股完整详情（聚合）
 * 后端 realtime/indicator 可能为 null（对应表无数据），需容错
 */
@Serializable
data class StockDetail(
    val info: StockInfo? = null,
    val realtime: RealtimeQuote? = null,
    val kline: List<KLineData>? = null,
    val indicator: TechnicalIndicator? = null
)

/**
 * AI 分析响应（data 内）
 * 后端 data 结构：{ code, name, analysis: {...}, cards: [...] }
 */
@Serializable
data class AIAnalysisResponse(
    val code: String,
    val name: String? = null,
    val analysis: Map<String, String> = emptyMap(),
    val cards: List<Map<String, JsonElement>> = emptyList()
)

/**
 * 聊天接口完整响应（data 内）
 * 后端 data 结构：{ session_id, message_id, reply: { text, cards, suggestions } }
 */
@Serializable
data class ChatResponse(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("message_id") val messageId: String = "",
    val reply: ChatReply = ChatReply("")
)

/**
 * AI 聊天回复
 * cards 用 JsonElement 承载，兼容后端卡片里的嵌套数组（signals、chart data）
 */
@Serializable
data class ChatReply(
    val text: String = "",
    val cards: List<Map<String, JsonElement>>? = null,
    val suggestions: List<String>? = null
)

/**
 * AI 服务状态（连接检测用）
 * 对应后端 GET /api/v1/ai/status 的 data 结构
 */
@Serializable
data class AiStatusResponse(
    val llm: AiLlmInfo = AiLlmInfo(),
    @SerialName("data_mode") val dataMode: String = "",
    val database: String = "",
    @SerialName("local_data") val localData: AiLocalDataInfo = AiLocalDataInfo()
)

@Serializable
data class AiLlmInfo(
    @SerialName("base_url") val baseUrl: String = "",
    val model: String = "",
    @SerialName("api_key_configured") val apiKeyConfigured: Boolean = false
)

@Serializable
data class AiLocalDataInfo(
    val available: Boolean = false,
    val stocks: Int = 0,
    @SerialName("kline_codes") val klineCodes: Int = 0
)
