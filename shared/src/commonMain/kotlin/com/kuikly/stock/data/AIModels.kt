package com.kuikly.stock.data

import kotlinx.serialization.Serializable

/**
 * AI 分析卡片基类
 */
@Serializable
data class AICard(
    val type: String,  // trend_card, signal_card, risk_card, suggestion_card, summary_card
    val title: String,
    val content: String? = null,
    val color: String? = null,
    // 扩展字段
    val extra: Map<String, Any?> = emptyMap()
)

/**
 * AI 个股分析响应
 */
@Serializable
data class AIAnalysisResponse(
    val code: String,
    val name: String? = null,
    val analysis: Map<String, Any?> = emptyMap(),
    val cards: List<Map<String, Any?>> = emptyList()
)

/**
 * 股票信息卡片（用于聊天回复）
 */
@Serializable
data class StockCard(
    val type: String = "stock_card",
    val code: String,
    val name: String,
    val price: String? = null,
    val changePercent: String? = null,
    val clickable: Boolean = true
)

/**
 * 图表卡片（用于聊天回复）
 */
@Serializable
data class ChartCard(
    val type: String = "chart_card",
    val title: String,
    val chartType: String,  // line, bar, pie
    val data: List<Map<String, Any>> = emptyList()
)

/**
 * AI 聊天回复
 */
@Serializable
data class ChatReply(
    val text: String,
    val cards: List<Map<String, Any?>>? = null,
    val suggestions: List<String>? = null
)

/**
 * 聊天接口完整响应
 */
@Serializable
data class ChatResponse(
    val sessionId: String,
    val messageId: String,
    val reply: ChatReply
)

/**
 * 聊天消息
 */
@Serializable
data class ChatMessage(
    val role: String,  // user, assistant
    val content: String,
    val cards: List<Map<String, Any?>>? = null,
    val timestamp: String? = null
)
