// 保存全局 Context 并打开本地数据库。

package com.kuikly.stock.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.database.Cursor
import com.kuikly.stock.pages.FundFlowItem
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.KLineDataItem
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockInfoData
import com.kuikly.stock.pages.StockListItem
import java.io.File

private const val TAG = "StockDb"
private const val ASSET_NAME = "stock.db"
private const val CACHED_NAME = "stock.db"

private var appContext: Context? = null
private var cachedPath: String? = null
private var dbAvailable = false

fun initStockDb(context: Context) {
    appContext = context.applicationContext
    ensureDb()
}

internal fun stockDbContext(): Context? = appContext

private fun ensureDb() {
    val ctx = appContext ?: return
    if (cachedPath != null) return
    try {
        val outFile = File(ctx.filesDir, CACHED_NAME)
        if (!outFile.exists()) {
            ctx.assets.open(ASSET_NAME).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        SQLiteDatabase.openDatabase(
            outFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { it.rawQuery("SELECT 1", null).use { c -> c.moveToFirst() } }
        cachedPath = outFile.absolutePath
        dbAvailable = true
        Log.i(TAG, "sqlite ready: " + outFile.absolutePath + " size=" + outFile.length())
    } catch (e: Exception) {
        dbAvailable = false
    }
}

private var dbInstance: SQLiteDatabase? = null

private fun openDb(): SQLiteDatabase? {
    ensureDb()
    val p = cachedPath ?: return null
    val cur = dbInstance
    if (cur != null && cur.isOpen) return cur
    dbInstance = try {
        SQLiteDatabase.openDatabase(p, null, SQLiteDatabase.OPEN_READONLY)
    } catch (e: Exception) { null }
    return dbInstance
}

actual object StockDb {

    actual fun isAvailable(): Boolean {
        ensureDb()
        Log.i(TAG, "isAvailable=" + dbAvailable + " cached=" + cachedPath)
        return dbAvailable
    }

    actual fun listStocks(
        keyword: String?, sort: String?, order: String?, page: Int, size: Int
    ): StockListPage {
        val db = openDb() ?: return StockListPage(0, emptyList())

        val where = StringBuilder()
        val args = mutableListOf<String>()
        if (!keyword.isNullOrBlank()) {
            val k = "%$keyword%"
            where.append(" WHERE (s.code LIKE ? OR s.name LIKE ?)")
            args.add(k); args.add(k)
        }

        val sortCol = when (sort) {
            "name" -> "s.name"
            "change_percent" -> "r.change_percent"
            "volume" -> "r.volume"
            "market_cap" -> "r.total_market_cap"
            else -> "s.code"
        }
        val dir = if (order.equals("asc", true)) "ASC" else "DESC"
        val orderClause = when {
            sort == null -> "ORDER BY s.code ASC"
            sort == "name" -> "ORDER BY $sortCol $dir"
            else -> "ORDER BY ($sortCol IS NULL) ASC, $sortCol $dir"
        }

        val total = db.rawQuery(
            "SELECT COUNT(*) AS c FROM stock_info s LEFT JOIN stock_realtime r ON s.code=r.code$where",
            args.toTypedArray()
        ).use { c -> if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow("c")) else 0 }

        val offset = (page - 1) * size
        val sql = """
            SELECT s.code, s.name, s.industry, s.plate, r.price, r.change, r.change_percent, r.volume
            FROM stock_info s LEFT JOIN stock_realtime r ON s.code = r.code
            $where $orderClause LIMIT ? OFFSET ?
        """.trimIndent()
        val qArgs = args.toMutableList().apply { add(size.toString()); add(offset.toString()) }
        val items = db.rawQuery(sql, qArgs.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(StockListItem(
                        code = c.getStringOrEmpty("code"),
                        name = c.getStringOrNull("name"),
                        price = c.getDoubleOrNull("price"),
                        changePercent = c.getDoubleOrNull("change_percent"),
                        change = c.getDoubleOrNull("change"),
                        volume = c.getDoubleOrNull("volume"),
                    ))
                }
            }
        }
    Log.i(TAG, "listStocks keyword=" + (keyword ?: "-") + " page=" + page + " -> total=" + total + " items=" + items.size)
        return StockListPage(total, items)
    }

    actual fun stockDetail(code: String): StockDetailData? {
        val db = openDb() ?: return null

        val info = db.rawQuery(
            "SELECT code,name,industry,plate,list_date FROM stock_info WHERE code=?",
            arrayOf(code)
        ).use { c ->
            if (!c.moveToFirst()) null
            else StockInfoData(
                code = c.getStringOrEmpty("code"),
                name = c.getStringOrNull("name"),
                industry = c.getStringOrNull("industry"),
                plate = c.getStringOrNull("plate"),
                listDate = c.getStringOrNull("list_date"),
            )
        }

        val realtime = db.rawQuery(
            """SELECT r.*, s.name FROM stock_realtime r
               LEFT JOIN stock_info s ON r.code=s.code
               WHERE r.code=? ORDER BY r.update_time DESC LIMIT 1""",
            arrayOf(code)
        ).use { c ->
            if (!c.moveToFirst()) null
            else RealtimeQuoteData(
                code = c.getStringOrEmpty("code"),
                name = c.getStringOrNull("name"),
                price = c.getDoubleOrNull("price"),
                change = c.getDoubleOrNull("change"),
                changePercent = c.getDoubleOrNull("change_percent"),
                openPrice = c.getDoubleOrNull("open"),
                preClose = c.getDoubleOrNull("pre_close"),
                high = c.getDoubleOrNull("high"),
                low = c.getDoubleOrNull("low"),
                volume = c.getDoubleOrNull("volume"),
                amount = c.getDoubleOrNull("amount"),
                peTtm = c.getDoubleOrNull("pe_ttm"),
                pb = c.getDoubleOrNull("pb"),
            )
        }

        val kline = db.rawQuery(
            "SELECT code,trade_date,open,close,high,low,volume,amount FROM stock_daily_kline " +
                "WHERE code=? ORDER BY trade_date DESC LIMIT 30",
            arrayOf(code)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(KLineDataItem(
                        code = c.getStringOrEmpty("code"),
                        tradeDate = c.getStringOrEmpty("trade_date"),
                        open = c.getDoubleOrZero("open"),
                        close = c.getDoubleOrZero("close"),
                        high = c.getDoubleOrZero("high"),
                        low = c.getDoubleOrZero("low"),
                        volume = c.getDoubleOrZero("volume"),
                        amount = c.getDoubleOrNull("amount"),
                    ))
                }
            }
        }.reversed()

    val indicator = latestIndicator(db, code)
    val fundFlow = fundFlow(code, 10)

        if (info == null && realtime == null) { Log.w(TAG, "stockDetail " + code + " NOT FOUND"); return null }
        Log.i(TAG, "stockDetail " + code + " -> info=" + (info != null) + " realtime=" + (realtime != null) + " kline=" + kline.size + " ind=" + indicator?.tradeDate + " ff=" + fundFlow.size)
        return StockDetailData(info = info, realtime = realtime, kline = kline, indicator = indicator, fundFlow = fundFlow)
    }

    actual fun indicators(code: String, limit: Int): List<IndicatorData> {
        val db = openDb() ?: return emptyList()
        return db.rawQuery(
            """SELECT trade_date,ma5,ma10,ma20,dif,dea,macd,rsi6,kdj_k,kdj_d,kdj_j
               FROM stock_indicator WHERE code=? ORDER BY trade_date DESC LIMIT ?""",
            arrayOf(code, limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(IndicatorData(
                        tradeDate = c.getStringOrEmpty("trade_date"),
                        ma5 = c.getDoubleOrNull("ma5"), ma10 = c.getDoubleOrNull("ma10"), ma20 = c.getDoubleOrNull("ma20"),
                        dif = c.getDoubleOrNull("dif"), dea = c.getDoubleOrNull("dea"), macd = c.getDoubleOrNull("macd"),
                        rsi6 = c.getDoubleOrNull("rsi6"),
                        kdjK = c.getDoubleOrNull("kdj_k"), kdjD = c.getDoubleOrNull("kdj_d"), kdjJ = c.getDoubleOrNull("kdj_j"),
                    ))
                }
            }
        }.reversed()
    }

    private fun latestIndicator(db: SQLiteDatabase, code: String): IndicatorData? {
        return db.rawQuery(
            """SELECT trade_date,ma5,ma10,ma20,dif,dea,macd,rsi6,kdj_k,kdj_d,kdj_j
               FROM stock_indicator WHERE code=? ORDER BY trade_date DESC LIMIT 1""",
            arrayOf(code)
        ).use { c ->
            if (!c.moveToFirst()) null
        else IndicatorData(
                tradeDate = c.getStringOrEmpty("trade_date"),
                ma5 = c.getDoubleOrNull("ma5"), ma10 = c.getDoubleOrNull("ma10"), ma20 = c.getDoubleOrNull("ma20"),
                dif = c.getDoubleOrNull("dif"), dea = c.getDoubleOrNull("dea"), macd = c.getDoubleOrNull("macd"),
                rsi6 = c.getDoubleOrNull("rsi6"),
                kdjK = c.getDoubleOrNull("kdj_k"), kdjD = c.getDoubleOrNull("kdj_d"), kdjJ = c.getDoubleOrNull("kdj_j"),
            )
        }
    }

    actual fun minute(code: String): List<MinutePoint> {
        val db = openDb() ?: return emptyList()
        val tradeDate = db.rawQuery(
            "SELECT trade_date FROM stock_minute WHERE code=? ORDER BY trade_date DESC LIMIT 1",
            arrayOf(code)
        ).use { c -> if (c.moveToFirst()) c.getStringOrNull("trade_date") else null } ?: return emptyList()

        return db.rawQuery(
            "SELECT time,price,avg_price,volume FROM stock_minute WHERE code=? AND trade_date=? ORDER BY time ASC LIMIT 240",
            arrayOf(code, tradeDate)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
            add(MinutePoint(
                        time = c.getStringOrEmpty("time"),
                        price = c.getDoubleOrZero("price"),
                        avgPrice = c.getDoubleOrNull("avg_price"),
                        volume = c.getDoubleOrNull("volume"),
                    ))
                }
            }
        }
    }

    actual fun orderBook(code: String): OrderBookData? {        val db = openDb() ?: return null
        return db.rawQuery(
            """SELECT update_time,bid1_price,bid1_vol,bid2_price,bid2_vol,bid3_price,bid3_vol,
                      bid4_price,bid4_vol,bid5_price,bid5_vol,ask1_price,ask1_vol,ask2_price,ask2_vol,
                      ask3_price,ask3_vol,ask4_price,ask4_vol,ask5_price,ask5_vol,commission_ratio
               FROM stock_order_book WHERE code=? ORDER BY update_time DESC LIMIT 1""",
            arrayOf(code)
        ).use { c ->
            if (!c.moveToFirst()) null
            else OrderBookData(
                updateTime = c.getStringOrNull("update_time"),
                bids = listOf(
                    c.getDoubleOrNull("bid1_price") to c.getDoubleOrNull("bid1_vol"),
                    c.getDoubleOrNull("bid2_price") to c.getDoubleOrNull("bid2_vol"),
                    c.getDoubleOrNull("bid3_price") to c.getDoubleOrNull("bid3_vol"),
                    c.getDoubleOrNull("bid4_price") to c.getDoubleOrNull("bid4_vol"),
                    c.getDoubleOrNull("bid5_price") to c.getDoubleOrNull("bid5_vol"),
                ),
                asks = listOf(
                    c.getDoubleOrNull("ask1_price") to c.getDoubleOrNull("ask1_vol"),
                    c.getDoubleOrNull("ask2_price") to c.getDoubleOrNull("ask2_vol"),
                    c.getDoubleOrNull("ask3_price") to c.getDoubleOrNull("ask3_vol"),
                    c.getDoubleOrNull("ask4_price") to c.getDoubleOrNull("ask4_vol"),
                    c.getDoubleOrNull("ask5_price") to c.getDoubleOrNull("ask5_vol"),
                ),
                commissionRatio = c.getDoubleOrNull("commission_ratio"),
            )
        }
    }

    actual fun fundFlow(code: String, limit: Int): List<FundFlowItem> {
        val db = openDb() ?: return emptyList()
        // 非必需表：旧库没有 stock_fund_flow 时静默返回空，不阻塞详情页
        return try {
            db.rawQuery(
                """SELECT trade_date,main_net,main_ratio,super_net,big_net,mid_net,small_net,source
                   FROM stock_fund_flow WHERE code=? ORDER BY trade_date DESC LIMIT ?""",
                arrayOf(code, limit.toString())
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(FundFlowItem(
                            tradeDate = c.getStringOrEmpty("trade_date"),
                            mainNet = c.getDoubleOrZero("main_net"),
                            mainRatio = c.getDoubleOrZero("main_ratio"),
                            superNet = c.getDoubleOrNull("super_net"),
                            bigNet = c.getDoubleOrNull("big_net"),
                            midNet = c.getDoubleOrNull("mid_net"),
                            smallNet = c.getDoubleOrNull("small_net"),
                            source = c.getStringOrNull("source") ?: "来源未提供",
                        ))
                    }
                }
            }.reversed()
        } catch (e: Exception) {
            Log.w(TAG, "fundFlow " + code + " 表缺失或查询失败: " + e.message)
            emptyList()
        }
    }

    actual fun industryPeers(code: String): IndustrySnapshot? {
        val db = openDb() ?: return null
        val industry = db.rawQuery("SELECT industry FROM stock_info WHERE code=?", arrayOf(code)).use {
            if (it.moveToFirst()) it.getStringOrNull("industry") else null
        }?.takeIf { it.isNotBlank() } ?: return null
        val members = db.rawQuery(
            """SELECT s.code,s.name,r.price,r.change_percent,r.update_time FROM stock_info s
               LEFT JOIN stock_realtime r ON r.code=s.code WHERE s.industry=? ORDER BY s.code""",
            arrayOf(industry)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(IndustryMember(
                    StockListItem(code = c.getStringOrEmpty("code"), name = c.getStringOrNull("name"),
                        price = c.getDoubleOrNull("price"), changePercent = c.getDoubleOrNull("change_percent")),
                    c.getStringOrNull("update_time").orEmpty().take(10)
                ))
            }
        }
        return IndustrySnapshot(industry, members)
    }

    actual fun sectorOfStock(code: String): SectorSnapshot? {
        val db = openDb() ?: return null
        // 1) 个股所属官方板块：stock_info.industry（东财行业名）与 sector_board.board_name 匹配，取最新快照
        val board = db.rawQuery(
            """SELECT b.board_code,b.board_name,b.change_percent,b.leader,b.leader_change,
                      b.total_mv,b.turnover,b.up_count,b.down_count,b.fetch_date
               FROM stock_info s JOIN sector_board b ON b.board_name=s.industry
               WHERE s.code=? ORDER BY b.fetch_date DESC LIMIT 1""",
            arrayOf(code)
        ).use { c ->
            if (!c.moveToFirst()) null
            else SectorBoardItem(
                boardCode = c.getStringOrEmpty("board_code"),
                boardName = c.getStringOrEmpty("board_name"),
                changePercent = c.getDoubleOrNull("change_percent"),
                leader = c.getStringOrNull("leader"),
                leaderChange = c.getDoubleOrNull("leader_change"),
                totalMv = c.getDoubleOrNull("total_mv"),
                turnover = c.getDoubleOrNull("turnover"),
                upCount = c.getIntOrNull("up_count") ?: 0,
                downCount = c.getIntOrNull("down_count") ?: 0,
                fetchDate = c.getStringOrEmpty("fetch_date"),
            )
        } ?: return null
        // 2) 板块成分股：该板块最新快照
        val members = db.rawQuery(
            """SELECT code,name,price,change_percent FROM sector_member
               WHERE board_code=? AND fetch_date=(SELECT MAX(fetch_date) FROM sector_member WHERE board_code=?)
               ORDER BY code""",
            arrayOf(board.boardCode, board.boardCode)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(SectorMemberItem(
                    code = c.getStringOrEmpty("code"),
                    name = c.getStringOrNull("name"),
                    price = c.getDoubleOrNull("price"),
                    changePercent = c.getDoubleOrNull("change_percent"),
                ))
            }
        }
        return SectorSnapshot(board, members)
    }

    actual fun marketOverview(): MarketOverview {
        val db = openDb() ?: return MarketOverview(0, 0, 0, 0, emptyList(), emptyList())
        val st = db.rawQuery(
            """SELECT COUNT(*) AS total,
               SUM(CASE WHEN change_percent>0 THEN 1 ELSE 0 END) AS up,
               SUM(CASE WHEN change_percent<0 THEN 1 ELSE 0 END) AS down,
               SUM(CASE WHEN change_percent=0 THEN 1 ELSE 0 END) AS flat
               FROM stock_realtime WHERE change_percent IS NOT NULL""",
            null
        ).use { c ->
            if (!c.moveToFirst()) null
            else Triple(c.getInt(c.getColumnIndexOrThrow("total")),
                c.getInt(c.getColumnIndexOrThrow("up")),
                c.getInt(c.getColumnIndexOrThrow("down")) to c.getInt(c.getColumnIndexOrThrow("flat")))
        }
        val (total, up, pair) = st ?: Triple(0, 0, 0 to 0)
        val (down, flat) = pair

        fun top(orderDir: String): List<StockListItem> = db.rawQuery(
            """SELECT r.code, s.name, r.price, r.change_percent FROM stock_realtime r
               LEFT JOIN stock_info s ON r.code=s.code
               WHERE r.change_percent IS NOT NULL
               ORDER BY r.change_percent $orderDir LIMIT 3""",
            null
        ).use { c ->
    buildList {
        while (c.moveToNext()) {
        add(StockListItem(
                        code = c.getStringOrEmpty("code"),
                        name = c.getStringOrNull("name"),
                        price = c.getDoubleOrNull("price"),
                        changePercent = c.getDoubleOrNull("change_percent"),
                    ))
                }
            }
        }

        return MarketOverview(
            total = total, up = up, down = down, flat = flat,
            topGainers = top("DESC"), topLosers = top("ASC"),
        )
    }

    actual fun detectMentioned(message: String): List<StockListItem> {
        val db = openDb() ?: return emptyList()
        val found = mutableListOf<StockListItem>()

        Regex("(?<!\\d)\\d{6}(?!\\d)").findAll(message).map { it.value }.toSet().forEach { code ->
        db.rawQuery("SELECT code,name FROM stock_info WHERE code=?", arrayOf(code)).use { c ->
        if (c.moveToFirst()) found.add(StockListItem(c.getStringOrEmpty("code"), c.getStringOrNull("name"), null, null))
            }
        }

        if (found.size < 3) {
        db.rawQuery("SELECT code,name FROM stock_info WHERE name IS NOT NULL AND name<>''", null).use { c ->
            while (c.moveToNext() && found.size < 3) {
            val name = c.getStringOrNull("name") ?: continue
            if (name.length >= 2 && name in message) {
        val code = c.getStringOrEmpty("code")
        if (found.none { it.code == code }) {
        found.add(StockListItem(code, name, null, null))
                        }
                    }
                }
            }
        }
        return found.take(3)
    }

    actual fun indexDetail(code: String): StockDetailData? {
        val db = openDb() ?: return null
        return try {
            val info = db.rawQuery(
                "SELECT code,name,market FROM index_info WHERE code=?",
                arrayOf(code)
            ).use { c ->
                if (!c.moveToFirst()) null
                else StockInfoData(
                    code = c.getStringOrEmpty("code"),
                    name = c.getStringOrNull("name"),
                    industry = null,
                    plate = c.getStringOrNull("market"),
                    listDate = null,
                )
            }

            val realtime = db.rawQuery(
                """SELECT r.*, s.name FROM index_realtime r
                   LEFT JOIN index_info s ON r.code=s.code
                   WHERE r.code=? ORDER BY r.update_time DESC LIMIT 1""",
                arrayOf(code)
            ).use { c ->
                if (!c.moveToFirst()) null
                else RealtimeQuoteData(
                    code = c.getStringOrEmpty("code"),
                    name = c.getStringOrNull("name"),
                    price = c.getDoubleOrNull("price"),
                    change = c.getDoubleOrNull("change"),
                    changePercent = c.getDoubleOrNull("change_percent"),
                    openPrice = c.getDoubleOrNull("open"),
                    preClose = c.getDoubleOrNull("pre_close"),
                    high = c.getDoubleOrNull("high"),
                    low = c.getDoubleOrNull("low"),
                    volume = c.getDoubleOrNull("volume"),
                    amount = c.getDoubleOrNull("amount"),
                    peTtm = c.getDoubleOrNull("pe_ttm"),
                    pb = c.getDoubleOrNull("pb"),
                )
            }

            val kline = db.rawQuery(
                "SELECT code,trade_date,open,close,high,low,volume,amount FROM index_daily_kline " +
                    "WHERE code=? ORDER BY trade_date DESC LIMIT 30",
                arrayOf(code)
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(KLineDataItem(
                            code = c.getStringOrEmpty("code"),
                            tradeDate = c.getStringOrEmpty("trade_date"),
                            open = c.getDoubleOrZero("open"),
                            close = c.getDoubleOrZero("close"),
                            high = c.getDoubleOrZero("high"),
                            low = c.getDoubleOrZero("low"),
                            volume = c.getDoubleOrZero("volume"),
                            amount = c.getDoubleOrNull("amount"),
                        ))
                    }
                }
            }.reversed()

            if (info == null && realtime == null) {
                Log.w(TAG, "indexDetail " + code + " NOT FOUND")
                return null
            }
            Log.i(TAG, "indexDetail " + code + " -> info=" + (info != null) + " realtime=" + (realtime != null) + " kline=" + kline.size)
            StockDetailData(info = info, realtime = realtime, kline = kline, indicator = null)
        } catch (e: Exception) {
            // 旧库无指数表时降级为空，由上层决定提示语
            Log.w(TAG, "indexDetail " + code + " failed: " + (e.message ?: e.toString()))
            null
        }
    }

    actual fun detectMentionedIndices(message: String): List<StockListItem> {
        val db = openDb() ?: return emptyList()
        return try {
            val candidates = db.rawQuery(
                "SELECT code,name FROM index_info WHERE name IS NOT NULL AND name<>''", null
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(IndexCandidate(c.getStringOrEmpty("code"), c.getStringOrNull("name")))
                    }
                }
            }
            matchIndexCandidates(message, candidates)
        } catch (e: Exception) {
            emptyList()
        }
    }

    actual fun listIndices(keyword: String?): List<StockListItem> {
        val db = openDb() ?: return emptyList()
        return try {
            val where = StringBuilder()
            val args = mutableListOf<String>()
            if (!keyword.isNullOrBlank()) {
                where.append(" WHERE (i.code LIKE ? OR i.name LIKE ?)")
                val k = "%$keyword%"
                args.add(k); args.add(k)
            }
            db.rawQuery(
                """SELECT i.code, i.name, r.price, r.change, r.change_percent, r.volume
                   FROM index_info i LEFT JOIN index_realtime r ON i.code=r.code""" + where +
                    " ORDER BY i.code ASC",
                if (args.isEmpty()) null else args.toTypedArray()
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(StockListItem(
                            code = c.getStringOrEmpty("code"),
                            name = c.getStringOrNull("name"),
                            price = c.getDoubleOrNull("price"),
                            changePercent = c.getDoubleOrNull("change_percent"),
                            change = c.getDoubleOrNull("change"),
                            volume = c.getDoubleOrNull("volume"),
                            isIndex = true,
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

        actual fun refreshFromFile(sourcePath: String): Boolean {
        val ctx = appContext ?: return false
        val src = File(sourcePath)
        if (!src.exists()) {
        Log.w(TAG, "refreshFromFile: source missing " + sourcePath)
        return false
        }
        val dest = File(ctx.filesDir, CACHED_NAME)

                if (!validateDb(src)) {
            Log.e(TAG, "refreshFromFile: 校验失败，保留旧库")
            src.delete()
            return false
        }

            val backup = File(ctx.filesDir, CACHED_NAME + ".bak")
        if (backup.exists()) backup.delete()
        if (dest.exists()) {
            if (!dest.renameTo(backup)) {
            copyTo(dest, backup); dest.delete()
            }
        }

        try {
                val moved = src.renameTo(dest)
            if (!moved) {
            copyTo(src, dest); src.delete()
            }
                dbInstance?.close(); dbInstance = null
            val conn = SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            conn.rawQuery("SELECT 1", null).use { c -> c.moveToFirst() }
            dbInstance = conn
            cachedPath = dest.absolutePath
            dbAvailable = true
            backup.delete()
            Log.i(TAG, "refreshFromFile OK size=" + dest.length())
            return true
        } catch (e: Exception) {
    Log.e(TAG, "refreshFromFile 失败，回滚旧库: " + (e.message ?: e.toString()), e)
    dbInstance?.close(); dbInstance = null
        if (backup.exists()) {
            if (dest.exists()) dest.delete()
            backup.renameTo(dest)
            }
            try {
                val conn = SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                conn.rawQuery("SELECT 1", null).use { c -> c.moveToFirst() }
                dbInstance = conn
                cachedPath = dest.absolutePath
                dbAvailable = true
            } catch (e2: Exception) {
                dbAvailable = false
            }
            return false
        }
    }

        private fun validateDb(f: File): Boolean {
        return try {
            val conn = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            conn.use {
                val integrity = it.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
                }
                if (integrity != "ok") {
                Log.e(TAG, "validateDb integrity_check=" + integrity)
                    return false
                }
                val required = setOf(
                    "stock_info", "stock_realtime", "stock_daily_kline",
                    "stock_indicator", "stock_minute", "stock_order_book", "data_source"
                )
            val tables = it.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
                }
        val missing = required - tables
    if (missing.isNotEmpty()) {
    Log.e(TAG, "validateDb 缺表: " + missing.joinToString(","))
        return false
                }
    val n = it.rawQuery("SELECT COUNT(*) FROM stock_info", null).use { c ->
    if (c.moveToFirst()) c.getInt(0) else 0
                }
        if (n < 100) {
            Log.e(TAG, "validateDb stock_info 行数过少=" + n)
                return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "validateDb 异常: " + (e.message ?: e.toString()), e)
            false
        }
    }

    private fun copyTo(src: File, dst: File) {
    src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
    }

    actual fun latestTradeDate(): String {
        val db = openDb() ?: return ""
        return try {
            // 与个股详情页同源：都读 stock_daily_kline，全库取 MAX。
            // 原实现按「自选第一只股票」取值：那只股票若落后一天，首页就整体落后一天
            // （实测 25 只已到 09-11、其余停在 09-10，首页会显示 09-10）。
            db.rawQuery("SELECT MAX(trade_date) AS d FROM stock_daily_kline", null).use { c ->
                if (c.moveToFirst()) c.getStringOrEmpty("d") else ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "latestTradeDate 异常: " + (e.message ?: e.toString()))
            ""
        }
    }

    actual fun dataSources(): List<Pair<String, String>> {
    val db = openDb() ?: return emptyList()
    return try {
        db.rawQuery("SELECT table_name, source FROM data_source ORDER BY table_name", null).use { c ->
        buildList {
        while (c.moveToNext()) {
    add(c.getStringOrEmpty("table_name") to (c.getStringOrNull("source") ?: ""))
                    }
                }
            }
        } catch (e: Exception) {
emptyList()
        }
    }

private fun Cursor.getStringOrNull(col: String): String? {
val i = getColumnIndex(col)
    return if (i < 0 || isNull(i)) null else getString(i)
    }
    private fun Cursor.getStringOrEmpty(col: String): String = getStringOrNull(col) ?: ""
private fun Cursor.getDoubleOrNull(col: String): Double? {
val i = getColumnIndex(col)
return if (i < 0 || isNull(i)) null else getDouble(i)
    }
private fun Cursor.getDoubleOrZero(col: String): Double = getDoubleOrNull(col) ?: 0.0
private fun Cursor.getIntOrNull(col: String): Int? {
val i = getColumnIndex(col)
return if (i < 0 || isNull(i)) null else getInt(i)
    }
}

private const val WATCH_PREFS = "kuikly_watch_prefs"

internal actual fun appPrefsGet(key: String): String? {
val ctx = appContext ?: return null
return ctx.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).getString(key, null)
}

internal actual fun appPrefsSet(key: String, value: String) {
val ctx = appContext ?: return
ctx.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).edit().putString(key, value).commit()
}
