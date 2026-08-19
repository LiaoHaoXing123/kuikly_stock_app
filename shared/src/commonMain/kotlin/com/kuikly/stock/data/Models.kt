package com.kuikly.stock.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 */
@Serializable
data class StockInfo(
    val code: String,
    val name: String? = null,
    val industry: String? = null,
    val plate: String? = null,
    @SerialName("list_date") val listDate: String? = null
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
 * 个股完整详情（聚合）
 * 后端 realtime 可能为 null（行情表无数据），需容错
 */
@Serializable
data class StockDetail(
    val info: StockInfo? = null,
    val realtime: RealtimeQuote? = null,
    val kline: List<KLineData>? = null
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
    val cards: List<Map<String, String>> = emptyList()
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
 */
@Serializable
data class ChatReply(
    val text: String = "",
    val cards: List<Map<String, String>>? = null,
    val suggestions: List<String>? = null
)
