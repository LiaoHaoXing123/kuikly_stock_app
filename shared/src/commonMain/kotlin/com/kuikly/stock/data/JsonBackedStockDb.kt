// 无 SQLite 平台（iOS / JS）的行情库实现。
//
// 这些平台随包内置以下 JSON 资产（iOS 经 CocoaPods resources 打包，JS 经同步 XHR 加载），
// 本实现直接复用 LocalDataService 的解析能力，语义与 Android 的 SQLite 实现保持一致
// （排序键、分页规则、涨跌统计口径、mention 上限均为 3）：
//
//   stock_list.json     个股快照（含 industry / update_time）
//   stock_kline.json    内置日 K 线
//   index_list.json     指数快照（index_info LEFT JOIN index_realtime）
//   sector_list.json    官方行业板块 + 成分股（sector_board / sector_member）
//   fundflow_list.json  个股资金流（stock_fund_flow）
//
// 后三个由 data-pipeline/export_common_assets.py 从 stock.db 导出，用于补齐
// iOS / JS 原本缺失的指数页、板块卡、资金流卡（此前一律返回空，只有空态）。
// 仍未支持的能力：分时 / 盘口（包内无分钟级数据）、refreshFromFile（无 SQLite 可导入），
// 调用方均有空态兜底。

package com.kuikly.stock.data

import com.kuikly.stock.pages.FundFlowItem
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockInfoData
import com.kuikly.stock.pages.StockListItem

internal object JsonBackedStockDb {

    fun listStocks(
        keyword: String?,
        sort: String?,
        order: String?,
        page: Int,
        size: Int,
    ): StockListPage {
        val all = if (keyword.isNullOrBlank()) {
            LocalDataService.loadStockList()
        } else {
            LocalDataService.searchStocks(keyword)
        }
        val asc = order.equals("asc", ignoreCase = true)
        val sorted = when (sort) {
            null -> all.sortedBy { it.code }
            "name" -> sortNullable(all, asc) { it.name }
            "change_percent" -> sortNullable(all, asc) { it.changePercent }
            "volume" -> sortNullable(all, asc) { it.volume }
            // StockListItem 无市值字段，退化为按代码排序
            else -> if (asc) all.sortedBy { it.code } else all.sortedByDescending { it.code }
        }
        val from = ((page - 1).coerceAtLeast(0)) * size.coerceAtLeast(1)
        return StockListPage(total = sorted.size, items = sorted.drop(from).take(size.coerceAtLeast(1)))
    }

    private fun <T : Comparable<T>> sortNullable(
        list: List<StockListItem>,
        asc: Boolean,
        key: (StockListItem) -> T?,
    ): List<StockListItem> {
        val (nulls, nonNulls) = list.partition { key(it) == null }
        val sorted = if (asc) nonNulls.sortedBy(key) else nonNulls.sortedByDescending(key)
        return sorted + nulls
    }

    fun stockDetail(code: String): StockDetailData? =
        LocalDataService.loadStockDetail(code)

    // 暂无调用方：技术指标一览尚未接入 UI，返回空列表。
    fun indicators(code: String, limit: Int): List<IndicatorData> = emptyList()

    // 包内无分钟级数据，调用方（分时卡片）有空态兜底。
    fun minute(code: String): List<MinutePoint> = emptyList()

    // 包内无盘口数据，调用方（盘口卡片）判空隐藏。
    fun orderBook(code: String): OrderBookData? = null

    // 资金流：来自 fundflow_list.json（导出时已按 trade_date 倒序），口径同 Android 的
    // `ORDER BY trade_date DESC LIMIT ?`。包内无该股记录时返回空，卡片自动隐藏。
    fun fundFlow(code: String, limit: Int): List<FundFlowItem> =
        LocalDataService.loadFundFlowRows(code).take(limit.coerceAtLeast(0)).map {
            FundFlowItem(
                tradeDate = it.tradeDate,
                mainNet = it.mainNet,
                mainRatio = it.mainRatio,
                superNet = it.superNet,
                bigNet = it.bigNet,
                midNet = it.midNet,
                smallNet = it.smallNet,
                source = it.source,
            )
        }

    // 同业样本：直接由 stock_list.json 的 industry 字段聚合，无需额外资产。
    // 与 Android `WHERE s.industry=? ORDER BY s.code` 等价。
    fun industryPeers(code: String): IndustrySnapshot? = LocalDataService.industryPeers(code)

    // 官方板块：优先用导出时固化的「个股 -> 板块代码」映射（与 Android 的 JOIN 同口径，
    // 不受 stock_list.json 旧 industry 词表影响）；映射缺失时退回按行业名匹配。
    // 同名板块取快照日期最新的一条，等价于 Android 的 ORDER BY fetch_date DESC LIMIT 1。
    fun sectorOfStock(code: String): SectorSnapshot? {
        val all = LocalDataService.loadSectorBoards()
        val board = LocalDataService.boardCodeOf(code)?.let { bc -> all.firstOrNull { it.boardCode == bc } }
            ?: LocalDataService.industryOf(code)?.let { industry ->
                all.filter { it.boardName == industry }.maxByOrNull { it.fetchDate }
            }
            ?: return null
        return SectorSnapshot(
            board = SectorBoardItem(
                boardCode = board.boardCode,
                boardName = board.boardName,
                changePercent = board.changePercent,
                leader = board.leader,
                leaderChange = board.leaderChange,
                totalMv = board.totalMv,
                turnover = board.turnover,
                upCount = board.upCount,
                downCount = board.downCount,
                fetchDate = board.fetchDate,
            ),
            members = LocalDataService.loadSectorMembers(board.boardCode).map {
                SectorMemberItem(
                    code = it.code,
                    name = it.name,
                    price = it.price,
                    changePercent = it.changePercent,
                )
            },
        )
    }

    fun marketOverview(): MarketOverview {
        val withPct = LocalDataService.loadStockList().filter { it.changePercent != null }
        return MarketOverview(
            total = withPct.size,
            up = withPct.count { (it.changePercent ?: 0.0) > 0 },
            down = withPct.count { (it.changePercent ?: 0.0) < 0 },
            flat = withPct.count { (it.changePercent ?: 0.0) == 0.0 },
            topGainers = withPct.sortedByDescending { it.changePercent }.take(3),
            topLosers = withPct.sortedBy { it.changePercent }.take(3),
        )
    }

    fun detectMentioned(message: String): List<StockListItem> {
        val all = LocalDataService.loadStockList()
        val found = mutableListOf<StockListItem>()
        Regex("(?<!\\d)\\d{6}(?!\\d)").findAll(message).map { it.value }.toSet().forEach { code ->
            all.firstOrNull { it.code == code }?.let {
                found.add(StockListItem(it.code, it.name, null, null))
            }
        }
        if (found.size < 3) {
            for (item in all) {
                if (found.size >= 3) break
                val name = item.name ?: continue
                if (name.length >= 2 && name in message && found.none { it.code == item.code }) {
                    found.add(StockListItem(item.code, item.name, null, null))
                }
            }
        }
        return found.take(3)
    }

    // 指数详情：来自 index_list.json。与 Android 一致——只有 info、没有 realtime 时也要返回，
    // 由调用方决定展示成什么；两端都不提供指数日 K（库中 index_daily_kline 为空表）。
    fun indexDetail(code: String): StockDetailData? {
        val row = LocalDataService.loadIndexRows().firstOrNull { it.code == code } ?: return null
        return StockDetailData(
            info = StockInfoData(
                code = row.code,
                name = row.name,
                industry = null,
                plate = row.market,
                listDate = null,
            ),
            realtime = RealtimeQuoteData(
                code = row.code,
                name = row.name,
                price = row.price,
                change = row.change,
                changePercent = row.changePercent,
                openPrice = row.open,
                preClose = row.preClose,
                high = row.high,
                low = row.low,
                volume = row.volume,
                amount = row.amount,
                peTtm = null,
                pb = null,
            ),
            kline = emptyList(),
            indicator = null,
        )
    }

    // 与 Android 的 matchIndexCandidates 复用同一套消歧规则（全名 / 简称 / 别名 / 代码+语境）。
    fun detectMentionedIndices(message: String): List<StockListItem> {
        val candidates = LocalDataService.loadIndexRows()
            .filter { !it.name.isNullOrBlank() }
            .map { IndexCandidate(it.code, it.name) }
        return matchIndexCandidates(message, candidates)
    }

    // 与 Android 的 `WHERE (code LIKE %k% OR name LIKE %k%) ORDER BY code ASC` 等价。
    fun listIndices(keyword: String?): List<StockListItem> {
        val k = keyword?.trim().orEmpty()
        return LocalDataService.loadIndexRows()
            .filter { k.isEmpty() || it.code.contains(k) || it.name?.contains(k) == true }
            .map { row ->
                StockListItem(
                    code = row.code,
                    name = row.name,
                    price = row.price,
                    changePercent = row.changePercent,
                    change = row.change,
                    volume = row.volume,
                    isIndex = true,
                )
            }
    }

    fun isAvailable(): Boolean = LocalDataService.loadStockList().isNotEmpty()

    // 无 SQLite 可导入，始终返回 false（调用方视为「数据已是最新」）。
    fun refreshFromFile(sourcePath: String): Boolean = false

    fun dataSources(): List<Pair<String, String>> = listOf(
        "stock_list.json" to "内置 A 股快照",
        "stock_kline.json" to "内置日 K 线",
        "index_list.json" to "内置指数快照",
        "sector_list.json" to "内置官方行业板块",
        "fundflow_list.json" to "内置个股资金流",
    )
}
