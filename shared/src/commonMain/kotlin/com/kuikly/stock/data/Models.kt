package com.kuikly.stock.data

import kotlinx.serialization.Serializable

/**
 * 股票基础信息
 */
@Serializable
data class StockInfo(
    val code: String,
    val name: String? = null,
    val industry: String? = null,
    val plate: String? = null,
    val listDate: String? = null
)

/**
 * 实时行情快照
 */
@Serializable
data class RealtimeQuote(
    val code: String,
    val name: String? = null,
    val price: Double? = null,
    val change: Double? = null,
    val changePercent: Double? = null,
    val openPrice: Double? = null,
    val preClose: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val volume: Double? = null,
    val amount: Double? = null,
    val turnoverRate: Double? = null,
    val volumeRatio: Double? = null,
    val peTtm: Double? = null,
    val pb: Double? = null,
    val totalMarketCap: Double? = null,
    val circulateMarketCap: Double? = null,
    val updateTime: String? = null
)

/**
 * K线数据
 */
@Serializable
data class KLineData(
    val code: String,
    val tradeDate: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double? = null
)

/**
 * 个股完整详情
 */
@Serializable
data class StockDetail(
    val info: StockInfo? = null,
    val realtime: RealtimeQuote? = null,
    val kline: List<KLineData>? = null
)

/**
 * 分页响应
 */
@Serializable
data class PaginatedResponse<T>(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val items: List<T>
)

/**
 * 通用 API 响应
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean = true,
    val message: String = "ok",
    val data: T? = null
)
