package com.kuikly.stock.data

import com.kuikly.stock.network.ApiService
import com.kuikly.stock.network.ChatReplyVO
import com.kuikly.stock.network.StockDetailVO
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

    /** AI 问答 */
    suspend fun chat(message: String): ChatResult {
        return if (DataSourceManager.isOnline) {
            try {
                ApiService.chat(message).toChatResult()
            } catch (e: Throwable) {
                LocalDataService.mockChat(message).withFallbackNote()
            }
        } else {
            LocalDataService.mockChat(message)
        }
    }

    // ==================== 离线分支 ====================

    private fun offlineList(keyword: String?): List<StockListItem> {
        val k = keyword ?: ""
        return if (k.isBlank()) LocalDataService.loadStockList()
        else LocalDataService.searchStocks(k)
    }

    private fun ChatResult.withFallbackNote(): ChatResult = copy(
        text = text + "  [提示] 在线模式未检测到后端服务，已回退为离线数据。"
    )

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
        cards = cards?.map { m -> m.mapValues { it.value as Any? } },
        suggestions = suggestions
    )
}
