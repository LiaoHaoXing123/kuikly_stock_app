// 为 iOS 和 Web 提供内置 JSON 行情查询。

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

    fun latestTradeDate(): String = LocalDataService.latestTradeDate()

    fun indicators(code: String, limit: Int): List<IndicatorData> = emptyList()

    fun minute(code: String): List<MinutePoint> = emptyList()

    fun orderBook(code: String): OrderBookData? = null

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

    fun industryPeers(code: String): IndustrySnapshot? = LocalDataService.industryPeers(code)

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

    fun detectMentionedIndices(message: String): List<StockListItem> {
        val candidates = LocalDataService.loadIndexRows()
            .filter { !it.name.isNullOrBlank() }
            .map { IndexCandidate(it.code, it.name) }
        return matchIndexCandidates(message, candidates)
    }

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

    fun refreshFromFile(sourcePath: String): Boolean = false

    fun dataSources(): List<Pair<String, String>> = listOf(
        "stock_list.json" to "内置 A 股快照",
        "stock_kline.json" to "内置日 K 线",
        "index_list.json" to "内置指数快照",
        "sector_list.json" to "内置官方行业板块",
        "fundflow_list.json" to "内置个股资金流",
    )
}
