package com.kuikly.stock.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.database.Cursor
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.KLineDataItem
import com.kuikly.stock.pages.MinutePoint
import com.kuikly.stock.pages.OrderBookData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockInfoData
import com.kuikly.stock.pages.StockListItem
import java.io.File

/**
 * Android 平台 StockDb 实现
 *
 * 数据来源：APK assets/stock.db（5MB SQLite 单文件）。
 * 首次使用时把 assets 拷到 filesDir（因 SQLiteDatabase 需要文件路径），
 * 之后直接以只读模式打开。assets/stock.db 更新（APK 重装）后会自动重新拷贝。
 *
 * 初始化：在 Application.onCreate 中调用顶层 [initStockDb]（与 initLocalDataService 同模式）。
 */
private const val TAG = "StockDb"
private const val ASSET_NAME = "stock.db"
private const val CACHED_NAME = "stock.db"

private var appContext: Context? = null
private var cachedPath: String? = null
private var dbAvailable = false

/** 在 Application.onCreate 中调用，注入 Android Context 并预拷贝数据库 */
fun initStockDb(context: Context) {
    appContext = context.applicationContext
    ensureDb()
}

private fun ensureDb() {
    val ctx = appContext ?: return
    if (cachedPath != null) return
    try {
        val outFile = File(ctx.filesDir, CACHED_NAME)
        // 判断是否需要拷贝：缓存缺失 或 资产大小与缓存不一致（保证 APK 数据更新后生效）。
        // openFd 仅在资产未压缩（androidResources.noCompress += "db"）时可用；
        // 若不可用则保守按"缓存缺失即拷贝"处理，避免每次启动都重拷贝。
        var needCopy = !outFile.exists()
        try {
            val assetSize = ctx.assets.openFd(ASSET_NAME).use { it.length }
            needCopy = needCopy || outFile.length() != assetSize
        } catch (e: Exception) {
            // openFd 不可用（资产被压缩）：无法比对大小，缓存已存在即视为就绪
        }
        if (needCopy) {
            ctx.assets.open(ASSET_NAME).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // 试打开验证可用性
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

/**
 * 打开只读数据库连接。
 * 复用同一个连接（App 生命周期内），避免每次查询 openDatabase 产生连接泄漏
 * （logcat 会报 "SQLiteConnection ... was leaked!"）；连接在进程结束由系统回收。
 */
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

    // ==================== 查询实现 ====================

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
        // 排序时把 NULL 值排到最后（LEFT JOIN 无实时数据的股票），避免涨幅/成交量排序时 NULL 顶到最前
        val orderClause = when {
            sort == null -> "ORDER BY s.code ASC"
            sort == "name" -> "ORDER BY $sortCol $dir"
            else -> "ORDER BY ($sortCol IS NULL) ASC, $sortCol $dir"
        }

        // 总数
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

        // 基础信息
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

        // 实时行情（JOIN 补 name）
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

        // 最近30日K线（升序）
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

        if (info == null && realtime == null) { Log.w(TAG, "stockDetail " + code + " NOT FOUND"); return null }
        Log.i(TAG, "stockDetail " + code + " -> info=" + (info != null) + " realtime=" + (realtime != null) + " kline=" + kline.size + " ind=" + indicator?.tradeDate)
        return StockDetailData(info = info, realtime = realtime, kline = kline, indicator = indicator)
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
        // 取最新交易日
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

    actual fun orderBook(code: String): OrderBookData? {
        val db = openDb() ?: return null
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

        // 6 位代码
        Regex("(?<!\\d)\\d{6}(?!\\d)").findAll(message).map { it.value }.toSet().forEach { code ->
            db.rawQuery("SELECT code,name FROM stock_info WHERE code=?", arrayOf(code)).use { c ->
                if (c.moveToFirst()) found.add(StockListItem(c.getStringOrEmpty("code"), c.getStringOrNull("name"), null, null))
            }
        }

        // 名称匹配（最多补到 3 只）
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

    // ==================== 数据更新（WorkManager 热切换） ====================

    /**
     * 用新下载的 SQLite 文件原子替换本地库并重新打开只读连接。
     * 流程：关旧连接 -> 拷贝到 filesDir/stock.db.new -> 删除旧库 -> 重命名 -> 重新打开校验。
     * 直接打开 dest（不经过 ensureDb 的 assets 拷贝，避免把刚下载的新数据又覆盖回旧资源）。
     */
    actual fun refreshFromFile(sourcePath: String): Boolean {
        val ctx = appContext ?: return false
        return try {
            dbInstance?.close()
            dbInstance = null
            val dest = File(ctx.filesDir, CACHED_NAME)
            val src = File(sourcePath)
            if (!src.exists()) {
                Log.w(TAG, "refreshFromFile: source missing " + sourcePath)
                return false
            }
            if (dest.exists()) dest.delete()
            // 把下载的源文件直接移动/拷贝到目标库（同目录 rename 是原子的）
            val moved = src.renameTo(dest)
            if (!moved) {
                src.copyTo(dest, overwrite = true)
                src.delete()
            }
            cachedPath = dest.absolutePath
            val newConn = SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            newConn.rawQuery("SELECT 1", null).use { c -> c.moveToFirst() }
            dbInstance = newConn
            dbAvailable = true
            Log.i(TAG, "refreshFromFile OK size=" + dest.length())
            true
        } catch (e: Exception) {
            dbAvailable = false
            Log.e(TAG, "refreshFromFile failed: " + (e.message ?: e.toString()), e)
            false
        }
    }

    /** 数据来源标注：data_source 表 table_name -> source（老库无此表时返回空） */
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

    // ==================== Cursor 取值工具 ====================

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
}
