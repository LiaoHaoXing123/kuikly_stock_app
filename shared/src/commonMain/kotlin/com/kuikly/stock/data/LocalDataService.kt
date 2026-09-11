// 本地数据服务：读取内置数据、保存设置与会话记录，并提供离线模式的模拟回答。

package com.kuikly.stock.data

import com.kuikly.stock.pages.StockListItem
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockInfoData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.KLineDataItem
import com.kuikly.stock.pages.AIAnalysisData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LocalDataService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var cachedStockList: List<StockListItem>? = null

    private var cachedKlines: Map<String, List<KLineRaw>>? = null

    fun loadStockList(): List<StockListItem> {
        cachedStockList?.let { return it }
        val raw = loadAssetText("stock_list.json") ?: return emptyList()
        val arr = json.parseToJsonElement(raw).jsonArray
        val list = arr.map { elem ->
            val obj = elem.jsonObject
            StockListItem(
                code = obj["code"]!!.jsonPrimitive.content,
                name = obj["name"]?.jsonPrimitive?.content,
                price = obj["price"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["_mock_price"]?.jsonPrimitive?.doubleOrNull,
                changePercent = obj["change_percent"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["_mock_change_percent"]?.jsonPrimitive?.doubleOrNull,
                change = obj["change"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["_mock_change"]?.jsonPrimitive?.doubleOrNull,
                volume = obj["volume"]?.jsonPrimitive?.doubleOrNull
            )
        }
        cachedStockList = list
        return list
    }

    fun searchStocks(keyword: String): List<StockListItem> {
        if (keyword.isBlank()) return loadStockList()
        val k = keyword.trim().uppercase()
        return loadStockList().filter {
            it.code.contains(k) || (it.name?.uppercase()?.contains(k) == true)
        }
    }

    fun loadStockDetail(code: String): StockDetailData? {
        val raw = loadAssetText("stock_list.json") ?: return null
        val arr = json.parseToJsonElement(raw).jsonArray
        val obj = arr.firstOrNull {
            it.jsonObject["code"]?.jsonPrimitive?.content == code
        }?.jsonObject ?: return null

        val name = obj["name"]?.jsonPrimitive?.content
        val price = obj["price"]?.jsonPrimitive?.doubleOrNull
            ?: obj["_mock_price"]?.jsonPrimitive?.doubleOrNull
            ?: 10.0
        val changePct = obj["change_percent"]?.jsonPrimitive?.doubleOrNull
            ?: obj["_mock_change_percent"]?.jsonPrimitive?.doubleOrNull
            ?: 0.0
        val change = obj["change"]?.jsonPrimitive?.doubleOrNull
            ?: (price * changePct / 100.0)

        val realtime = RealtimeQuoteData(
            code = code,
            name = name,
            price = price,
            change = round2(change),
            changePercent = round2(changePct),
            openPrice = obj["open"]?.jsonPrimitive?.doubleOrNull
                ?: round2(price * randomFactor(0.97, 1.03)),
            preClose = obj["pre_close"]?.jsonPrimitive?.doubleOrNull
                ?: round2(price / (1 + changePct / 100.0)),
            high = obj["high"]?.jsonPrimitive?.doubleOrNull
                ?: round2(price * randomFactor(1.0, 1.05)),
            low = obj["low"]?.jsonPrimitive?.doubleOrNull
                ?: round2(price * randomFactor(0.95, 1.0)),
            volume = obj["volume"]?.jsonPrimitive?.doubleOrNull
                ?: randomDouble(10000.0, 500000.0),
            amount = obj["amount"]?.jsonPrimitive?.doubleOrNull
                ?: randomDouble(8000000.0, 200000000.0),
            peTtm = obj["pe_ttm"]?.jsonPrimitive?.doubleOrNull
                ?: randomDouble(5.0, 80.0),
            pb = obj["pb"]?.jsonPrimitive?.doubleOrNull
                ?: randomDouble(0.5, 10.0)
        )

        val klineMap = loadAllKlines()
        val klineItems = klineMap[code]?.map {
            KLineDataItem(code = it.code, tradeDate = it.tradeDate,
                open = it.open, close = it.close, high = it.high,
                low = it.low, volume = it.volume, amount = it.amount)
        } ?: emptyList()

        val info = StockInfoData(
            code = obj["code"]!!.jsonPrimitive.content,
            name = name,
            industry = obj["industry"]?.jsonPrimitive?.content,
            plate = obj["plate"]?.jsonPrimitive?.content,
            listDate = obj["list_date"]?.jsonPrimitive?.content
        )

        return StockDetailData(info = info, realtime = realtime, kline = klineItems)
    }

    /**
     * 全库最新交易日：日线数据里最后一个交易日的最大值。
     *
     * 这是「数据到哪一天」的唯一口径——个股详情页展示的也是同一张日线表里该股的最后一个交易日，
     * 所以只要数据是一次管道跑出来的，两边必然一致。取不到返回空串（界面显示「待更新」）。
     */
    fun latestTradeDate(): String =
        loadAllKlines().values.asSequence()
            .mapNotNull { it.lastOrNull()?.tradeDate?.takeIf { d -> d.isNotBlank() } }
            .maxOrNull()
            .orEmpty()

    private fun loadAllKlines(): Map<String, List<KLineRaw>> {
        cachedKlines?.let { return it }
        val raw = loadAssetText("stock_kline.json") ?: return emptyMap()
        val root = json.parseToJsonElement(raw).jsonObject
        val map = mutableMapOf<String, List<KLineRaw>>()
        for ((code, value) in root) {
            val arr = value.jsonArray
            map[code] = arr.map { elem ->
                val o = elem.jsonObject
                KLineRaw(
                    code = o["code"]!!.jsonPrimitive.content,
                    tradeDate = o["trade_date"]!!.jsonPrimitive.content,
                    open = o["open"]!!.jsonPrimitive.double,
                    close = o["close"]!!.jsonPrimitive.double,
                    high = o["high"]!!.jsonPrimitive.double,
                    low = o["low"]!!.jsonPrimitive.double,
                    volume = o["volume"]!!.jsonPrimitive.int.toDouble(),
                    amount = o["amount"]?.jsonPrimitive?.doubleOrNull
                )
            }
        }
        cachedKlines = map
        return map
    }

    // ------------------------------------------------------------------
    // JSON 兜底数据（无 SQLite 平台：iOS / JS）
    //
    // Android 的指数 / 官方板块 / 资金流来自 stock.db 的 index_* / sector_* /
    // stock_fund_flow 表；iOS、JS 没有 SQLite，改由内置 JSON 资产提供：
    //   index_list.json / sector_list.json / fundflow_list.json
    // 这三个文件由 data-pipeline/export_common_assets.py 从 stock.db 导出，
    // 口径与 Android 侧查询严格对齐（详见脚本 docstring 与 JsonBackedStockDb）。
    //
    // 体积大的两张表用「紧凑数组」编码（键名只出现一次），因此下面的解析是按下标取值，
    // 下标含义与脚本中的列顺序一一对应，改动任一侧都要同步。
    // ------------------------------------------------------------------

    private var cachedIndexRows: List<IndexRow>? = null
    private var cachedSectorBoards: List<SectorBoardRow>? = null
    private var cachedSectorMemberMap: Map<String, List<SectorMemberRow>>? = null
    private var cachedFundFlowRows: Map<String, List<FundFlowRow>>? = null

    /** index_list.json：index_info LEFT JOIN index_realtime，按 code 升序。 */
    internal fun loadIndexRows(): List<IndexRow> {
        cachedIndexRows?.let { return it }
        val raw = loadAssetText("index_list.json") ?: return emptyList()
        val list = runCatching {
            json.parseToJsonElement(raw).jsonArray.map { elem ->
                val o = elem.jsonObject
                IndexRow(
                    code = o.str("code").orEmpty(),
                    symbol = o.str("symbol"),
                    name = o.str("name"),
                    market = o.str("market"),
                    price = o.num("price"),
                    change = o.num("change"),
                    changePercent = o.num("change_percent"),
                    open = o.num("open"),
                    preClose = o.num("pre_close"),
                    high = o.num("high"),
                    low = o.num("low"),
                    volume = o.num("volume"),
                    amount = o.num("amount"),
                    updateTime = o.str("update_time"),
                )
            }
        }.getOrNull() ?: emptyList()
        cachedIndexRows = list
        return list
    }

    /** sector_list.json -> boards：行业名可匹配到个股 industry 的官方板块。 */
    internal fun loadSectorBoards(): List<SectorBoardRow> {
        cachedSectorBoards?.let { return it }
        val raw = loadAssetText("sector_list.json") ?: return emptyList()
        val list = runCatching {
            json.parseToJsonElement(raw).jsonObject["boards"]?.jsonArray?.map { elem ->
                val o = elem.jsonObject
                SectorBoardRow(
                    boardCode = o.str("board_code").orEmpty(),
                    boardName = o.str("board_name").orEmpty(),
                    changePercent = o.num("change_percent"),
                    leader = o.str("leader"),
                    leaderChange = o.num("leader_change"),
                    totalMv = o.num("total_mv"),
                    turnover = o.num("turnover"),
                    upCount = o["up_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    downCount = o["down_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    fetchDate = o.str("fetch_date").orEmpty(),
                )
            }
        }.getOrNull() ?: emptyList()
        cachedSectorBoards = list
        return list
    }

    /** sector_list.json -> members[boardCode]，紧凑数组：[代码, 名称, 最新价, 涨跌幅%]。 */
    internal fun loadSectorMembers(boardCode: String): List<SectorMemberRow> =
        loadSectorMemberMap()[boardCode].orEmpty()

    private var cachedStockBoard: Map<String, String>? = null

    /**
     * sector_list.json -> stock_board：个股代码 -> 官方板块代码。
     *
     * Android 是用 `stock_info.industry = sector_board.board_name` 现算 JOIN 的，
     * iOS/JS 只有 stock_list.json，而它的 industry 词表是旧快照（粒度与当前库不同），
     * 按名称匹配会选到另一个板块。因此导出脚本把这层 JOIN 结果固化成映射，
     * 两端查询板块的结果就此完全一致。
     */
    internal fun boardCodeOf(code: String): String? = loadStockBoardMap()[code]

    private fun loadStockBoardMap(): Map<String, String> {
        cachedStockBoard?.let { return it }
        val raw = loadAssetText("sector_list.json") ?: return emptyMap()
        val map = runCatching {
            json.parseToJsonElement(raw).jsonObject["stock_board"]?.jsonObject
                ?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() }
                ?.filterValues { it.isNotEmpty() }
        }.getOrNull() ?: emptyMap()
        cachedStockBoard = map
        return map
    }

    private fun loadSectorMemberMap(): Map<String, List<SectorMemberRow>> {
        cachedSectorMemberMap?.let { return it }
        val raw = loadAssetText("sector_list.json") ?: return emptyMap()
        val map = runCatching {
            json.parseToJsonElement(raw).jsonObject["members"]?.jsonObject?.mapValues { (_, v) ->
                v.jsonArray.map { row ->
                    val a = row.jsonArray
                    SectorMemberRow(
                        code = a.strAt(0).orEmpty(),
                        name = a.strAt(1),
                        price = a.numAt(2),
                        changePercent = a.numAt(3),
                    )
                }
            }
        }.getOrNull() ?: emptyMap()
        cachedSectorMemberMap = map
        return map
    }

    /**
     * fundflow_list.json -> code 对应行，紧凑数组：
     * [日期, 主力净额, 主力占比, 超大单, 大单, 中单, 小单, 来源]，日期倒序。
     */
    internal fun loadFundFlowRows(code: String): List<FundFlowRow> {
        cachedFundFlowRows?.let { return it[code].orEmpty() }
        val raw = loadAssetText("fundflow_list.json") ?: return emptyList()
        val map = runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) ->
                v.jsonArray.map { row ->
                    val a = row.jsonArray
                    FundFlowRow(
                        tradeDate = a.strAt(0).orEmpty(),
                        mainNet = a.numAt(1) ?: 0.0,
                        mainRatio = a.numAt(2) ?: 0.0,
                        superNet = a.numAt(3),
                        bigNet = a.numAt(4),
                        midNet = a.numAt(5),
                        smallNet = a.numAt(6),
                        source = a.strAt(7) ?: "来源未提供",
                    )
                }
            }
        }.getOrNull() ?: emptyMap()
        cachedFundFlowRows = map
        return map[code].orEmpty()
    }

    // ---- 行业索引：从 stock_list.json 建立「行业 -> 同业个股」，供 iOS/JS 的同业卡使用 ----
    // 注意 StockListItem 不透出 industry 字段，所以这里单独解析一次原始 JSON。

    private var industryByCode: Map<String, String>? = null
    private var industryMemberMap: Map<String, List<IndustryMember>>? = null

    private fun ensureIndustryIndex() {
        if (industryByCode != null) return
        val byCode = mutableMapOf<String, String>()
        val members = mutableMapOf<String, MutableList<IndustryMember>>()
        val raw = loadAssetText("stock_list.json")
        val arr = if (raw == null) emptyList() else runCatching {
            json.parseToJsonElement(raw).jsonArray
        }.getOrDefault(emptyList())
        for (elem in arr) {
            val o = elem.jsonObject
            val code = o.str("code") ?: continue
            val industry = o.str("industry")?.takeIf { it.isNotBlank() } ?: continue
            byCode[code] = industry
            members.getOrPut(industry) { mutableListOf() }.add(
                IndustryMember(
                    quote = StockListItem(
                        code = code,
                        name = o.str("name"),
                        price = o.num("price") ?: o.num("_mock_price"),
                        changePercent = o.num("change_percent") ?: o.num("_mock_change_percent"),
                    ),
                    date = o.str("update_time").orEmpty().take(10),
                )
            )
        }
        industryByCode = byCode
        // 与 Android 的 ORDER BY s.code 对齐，保证两端排名/顺序一致
        industryMemberMap = members.mapValues { (_, v) -> v.sortedBy { it.quote.code } }
    }

    /** 个股所属行业名（stock_list.json 的 industry 字段）。 */
    fun industryOf(code: String): String? {
        ensureIndustryIndex()
        return industryByCode?.get(code)
    }

    /** 与 Android StockDb.industryPeers 等价：该股所属行业的全部个股快照。 */
    fun industryPeers(code: String): IndustrySnapshot? {
        val industry = industryOf(code) ?: return null
        return IndustrySnapshot(industry, industryMemberMap?.get(industry).orEmpty())
    }

    // 紧凑数组的取值辅助：下标越界或 JsonNull 都安全降级为 null
    // 注：JsonArray 是 List<JsonElement> 的 typealias，这里直接用底层类型写接收者。
    private fun List<kotlinx.serialization.json.JsonElement>.strAt(i: Int): String? =
        getOrNull(i)?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }
    private fun List<kotlinx.serialization.json.JsonElement>.numAt(i: Int): Double? =
        getOrNull(i)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    fun mockChat(message: String): ChatResult {
        val msg = message.trim()
        if (msg.isEmpty()) return generalHelp()

        val indices = runCatching { StockDb.detectMentionedIndices(msg) }.getOrDefault(emptyList())

        val codeHit = Regex("\\d{6}").find(msg)?.value?.let { code ->
            loadStockList().find { it.code == code }
        }

        val nameHit = if (codeHit == null) {
            loadStockList().firstOrNull { stock ->
                stock.name?.let { name -> msg.contains(name) } == true
            }
        } else null

        val target = codeHit ?: nameHit

        return when {
            indices.isNotEmpty() -> indexQa(indices.first())
            target != null -> stockQa(target)
            isMarketQuestion(msg) -> marketOverview()
            else -> generalHelp()
        }
    }

    private fun isMarketQuestion(msg: String): Boolean {
        val keywords = listOf("大盘", "行情", "市场", "指数", "涨幅榜", "跌幅榜", "涨跌", "板块")
        return keywords.any { msg.contains(it) }
    }

    private fun stockQa(stock: StockListItem): ChatResult {
        val code = stock.code
        val name = stock.name ?: code
        val detail = loadStockDetail(code)
        val info = detail?.info
        val industry = info?.industry ?: "未知"
        val plate = info?.plate ?: "未知"
        val listDate = info?.listDate ?: "未知"

        val text = "## $name($code) 基本面参考\n\n" +
            "**离线模式**：无法获取实时行情与价格，以下仅作基本面参考：\n\n" +
            "- 所属行业：**$industry**\n" +
            "- 所属板块：**$plate**\n" +
            "- 上市日期：**$listDate**\n\n" +
            "如需实时行情与买卖点分析，请切换回在线模式。"

        val cards = listOf<Map<String, Any?>>(
            mapOf(
                "type" to "stock_card",
                "code" to code,
                "name" to name,
                "price" to "-",
                "changePercent" to "-"
            ),
            mapOf("type" to "trend_card", "content" to "离线模式无法获取实时行情，暂不提供趋势判断。"),
            mapOf("type" to "signal_card", "content" to "离线模式：技术信号不可用，请联网查询实时数据。"),
            mapOf("type" to "suggestion_card", "content" to "离线模式：不提供买卖建议，请联网获取实时数据后决策。")
        )

        val suggestions = listOf(
            "$name 的基本面怎么样？",
            "$name 有哪些投资风险？",
            "今天大盘怎么样？"
        )

        return ChatResult(text = text, cards = cards, suggestions = suggestions)
    }

    private fun indexQa(index: StockListItem): ChatResult {
        val code = index.code
        val detail = runCatching { StockDb.indexDetail(code) }.getOrNull()
        val name = detail?.info?.name ?: index.name ?: code
        val r = detail?.realtime
        val text = if (r?.price != null) {
            "## $name($code) 指数行情\n\n" +
                "**本地指数库**（离线模板，仅展示本地快照）：\n\n" +
                "- 最新点位：**${fmt2(r.price ?: 0.0)}**\n" +
                "- 涨跌幅：**${fmt2(r.changePercent ?: 0.0)}%**\n" +
                "- 区间：最高 ${fmt2(r.high ?: 0.0)} / 最低 ${fmt2(r.low ?: 0.0)}\n\n" +
                "如需 AI 趋势解读，请切换回在线模式。"
        } else {
            "## $name($code) 指数\n\n" +
                "本地暂无该指数的行情快照（可能是旧版数据库，更新后重试）。\n\n" +
                "如需实时点位与走势分析，请切换回在线模式。"
        }
        val cards = listOf<Map<String, Any?>>(
            mapOf(
                "type" to "index_card",
                "code" to code,
                "name" to name,
                "price" to fmt2(r?.price ?: 0.0),
                "changePercent" to fmtSignedPct(r?.changePercent ?: 0.0)
            ),
            mapOf("type" to "signal_card", "content" to "离线模式：指数技术信号不可用，请联网查询实时数据。"),
            mapOf("type" to "risk_card", "content" to "离线模板不构成投资建议，指数投资同样有风险。")
        )
        val suggestions = listOf(
            "$name 近期走势怎么样？",
            "今天大盘怎么样？",
            "沪深300和上证指数有什么区别？"
        )
        return ChatResult(text = text, cards = cards, suggestions = suggestions)
    }

    private fun marketOverview(): ChatResult {
        val all = loadStockList()
        val up = all.count { (it.changePercent ?: 0.0) > 0 }
        val down = all.count { (it.changePercent ?: 0.0) < 0 }
        val flat = all.size - up - down

        val topGainers = all.sortedByDescending { it.changePercent ?: 0.0 }.take(3)
        val topLosers = all.sortedBy { it.changePercent ?: 0.0 }.take(3)

        val text = "## 今日市场概览\n\n" +
            "**离线模式**：无法获取实时涨跌数据，以下为本地样本统计参考：\n\n" +
            "- 样本股票：共 **${all.size}** 只\n" +
            "- 上涨（本地样本）：**$up** 家\n" +
            "- 下跌（本地样本）：**$down** 家\n\n" +
            "离线模式不提供实时价格与涨跌幅，请切换在线模式获取真实行情。"

        val cards = mutableListOf<Map<String, Any?>>(
            mapOf("type" to "chart_card", "title" to "市场概览", "chartType" to "bar")
        )

        val suggestions = listOf("涨幅榜有哪些？", "跌幅榜有哪些？", "帮我分析 600519")

        return ChatResult(text = text, cards = cards, suggestions = suggestions)
    }

    private fun generalHelp(): ChatResult {
        val text = "我是 **AI 股票助手**，可以帮你查询个股行情、解读趋势并给出操作参考。\n\n" +
            "试试输入股票代码或名称：\n" +
            "- 「帮我分析 600519」\n" +
            "- 「贵州茅台怎么样」"
        val suggestions = listOf("帮我分析 600519", "今天大盘怎么样？", "涨幅榜有哪些？", "贵州茅台怎么样？")
        return ChatResult(text = text, cards = null, suggestions = suggestions)
    }

    private fun stockCardOf(s: StockListItem): Map<String, Any?> = mapOf(
        "type" to "stock_card",
        "code" to s.code,
        "name" to (s.name ?: s.code),
        "price" to fmt2(s.price ?: 0.0),
        "changePercent" to (if ((s.changePercent ?: 0.0) >= 0) "+" else "") + fmt2(s.changePercent ?: 0.0) + "%"
    )

    suspend fun mockAnalysis(code: String): AIAnalysisData? {
        val detail = loadStockDetail(code) ?: return null
        return mockAnalysis(detail)
    }

    fun mockAnalysis(detail: StockDetailData): AIAnalysisData {
        val code = detail.info?.code ?: detail.realtime?.code.orEmpty()

        val name = detail.info?.name ?: "未知"
        val price = detail.realtime?.price ?: 0.0
        val changePct = detail.realtime?.changePercent ?: 0.0

        val trend = when {
            changePct > 3 -> "强势上涨"
            changePct > 0 -> "震荡偏多"
            changePct == 0.0 -> "横盘整理"
            changePct > -3 -> "弱势调整"
            else -> "大幅下跌"
        }

        val signals = mutableListOf<String>()
        if (price > 50) signals.add("高价股，注意波动风险")
        if (changePct > 2) signals.add("短期动能较强")
        if (changePct < -2) signals.add("短期承压明显")
        val kline = detail.kline ?: emptyList()
        if (kline.size >= 5) {
            val recent = kline.takeLast(5)
            val upCount = recent.count { it.close > it.open }
            if (upCount >= 4) signals.add("连续收阳，多头占优")
            else if (upCount <= 1) signals.add("连续收阴，空头主导")
        }
        if (signals.isEmpty()) signals.add("观望为主，等待方向选择")

        val suggestion = if (changePct > 1) "短线可持有，设好止损"
        else if (changePct < -1) "轻仓观望，等待企稳"
        else "保持现有仓位，控制风险"

        return AIAnalysisData(
            code = code,
            name = name,
            analysis = mapOf(
                "趋势判断" to trend,
                "最新价" to fmt2(price),
                "涨跌幅" to fmt2(changePct) + "%",
                "数据来源" to "本地离线数据（未能连接 AI 服务，模板回答）"
            ),
            cards = listOf(
                mapOf(
                    "type" to "trend_card", "title" to "趋势研判",
                    "content" to "$name 当前处于$trend 阶段，建议关注成交量变化和均线支撑。",
                    "color" to "#1976D2"
                ),
                mapOf(
                    "type" to "signal_card", "title" to "技术信号",
                    "signals" to signals.toMutableList<Any?>(), "color" to "#FF9800"
                ),
                mapOf(
                    "type" to "risk_card", "title" to "风险提示",
                    "content" to "本分析基于本地模板，不构成投资建议。股市有风险，投资需谨慎。",
                    "color" to StockColors.UP_HEX
                ),
                mapOf(
                    "type" to "suggestion_card", "title" to "操作建议",
                    "suggestion" to suggestion,
                    "target_price" to fmt2(price * 1.05),
                    "stop_loss" to fmt2(price * 0.95),
                    "color" to StockColors.DOWN_HEX
                ),
                mapOf(
                    "type" to "summary_card", "title" to "总结",
                    "content" to "$name($code): $trend | ${signals.size} 项技术信号 | 模板回答",
                    "color" to "#7B1FA2"
                )
            )
        )
    }

    suspend fun mockIndexAnalysis(code: String): AIAnalysisData? {
        val detail = runCatching { StockDb.indexDetail(code) }.getOrNull() ?: return null
        return mockIndexAnalysis(detail)
    }

    fun mockIndexAnalysis(detail: StockDetailData): AIAnalysisData {
        val code = detail.info?.code ?: detail.realtime?.code.orEmpty()

        val name = detail.info?.name ?: "未知指数"
        val price = detail.realtime?.price ?: 0.0
        val changePct = detail.realtime?.changePercent ?: 0.0

        val trend = when {
            changePct > 1 -> "强势上行"
            changePct > 0 -> "震荡偏强"
            changePct == 0.0 -> "横盘整理"
            changePct > -1 -> "震荡偏弱"
            else -> "明显走弱"
        }

        val signals = mutableListOf<String>()
        if (changePct > 1) signals.add("短期动能较强")
        if (changePct < -1) signals.add("短期承压明显")
        val kline = detail.kline ?: emptyList()
        if (kline.size >= 5) {
            val recent = kline.takeLast(5)
            val upCount = recent.count { it.close > it.open }
            if (upCount >= 4) signals.add("连续收阳，多头占优")
            else if (upCount <= 1) signals.add("连续收阴，空头主导")
        }
        if (signals.isEmpty()) signals.add("方向不明，观望为主")

        val suggestion = if (changePct > 0.5) "偏多思路，注意追高风险"
        else if (changePct < -0.5) "谨慎观望，等待企稳信号"
        else "区间震荡，不宜重仓押注方向"

        return AIAnalysisData(
            code = code,
            name = name,
            analysis = mapOf(
                "趋势判断" to trend,
                "最新点位" to fmt2(price),
                "涨跌幅" to fmt2(changePct) + "%",
                "数据来源" to "本地离线数据（未能连接 AI 服务，模板回答）"
            ),
            cards = listOf(
                mapOf(
                    "type" to "trend_card", "title" to "趋势研判",
                    "content" to "$name 当前处于$trend 阶段，重点观察量能配合与整数关口得失。",
                    "color" to "#1976D2"
                ),
                mapOf(
                    "type" to "signal_card", "title" to "技术信号",
                    "signals" to signals.toMutableList<Any?>(), "color" to "#FF9800"
                ),
                mapOf(
                    "type" to "risk_card", "title" to "风险提示",
                    "content" to "本分析基于本地模板，不构成投资建议。市场有风险，投资需谨慎。",
                    "color" to StockColors.UP_HEX
                ),
                mapOf(
                    "type" to "suggestion_card", "title" to "操作建议",
                    "suggestion" to suggestion,
                    "target_price" to fmt2(price * 1.03),
                    "stop_loss" to fmt2(price * 0.97),
                    "color" to StockColors.DOWN_HEX
                ),
                mapOf(
                    "type" to "summary_card", "title" to "总结",
                    "content" to "$name($code): $trend | ${signals.size} 项技术信号 | 模板回答",
                    "color" to "#7B1FA2"
                )
            )
        )
    }

    private fun round2(v: Double) = kotlin.math.round(v * 100.0) / 100.0
    private fun randomFactor(lo: Double, hi: Double) = kotlin.random.Random.nextDouble() * (hi - lo) + lo
    private fun randomDouble(lo: Double, hi: Double) = kotlin.random.Random.nextDouble() * (hi - lo) + lo

    internal data class KLineRaw(
        val code: String,
        val tradeDate: String,
        val open: Double,
        val close: Double,
        val high: Double,
        val low: Double,
        val volume: Double,
        val amount: Double?
    )
}

data class ChatResult(
    val text: String,
    val cards: List<Map<String, Any?>>? = null,
    val suggestions: List<String>? = null,
    val errorNotice: String? = null,
    val failed: Boolean = false,
)

// ---- 内置 JSON 资产的行类型（iOS / JS 无 SQLite 时的兜底数据源） ----

/** index_list.json 的一行，对应 index_info LEFT JOIN index_realtime。 */
internal data class IndexRow(
    val code: String,
    val symbol: String?,
    val name: String?,
    val market: String?,
    val price: Double?,
    val change: Double?,
    val changePercent: Double?,
    val open: Double?,
    val preClose: Double?,
    val high: Double?,
    val low: Double?,
    val volume: Double?,
    val amount: Double?,
    val updateTime: String?,
)

/** sector_list.json -> boards 的一行，对应 sector_board。 */
internal data class SectorBoardRow(
    val boardCode: String,
    val boardName: String,
    val changePercent: Double?,
    val leader: String?,
    val leaderChange: Double?,
    val totalMv: Double?,
    val turnover: Double?,
    val upCount: Int,
    val downCount: Int,
    val fetchDate: String,
)

/** sector_list.json -> members 的一项，对应 sector_member（紧凑数组解码后）。 */
internal data class SectorMemberRow(
    val code: String,
    val name: String?,
    val price: Double?,
    val changePercent: Double?,
)

/** fundflow_list.json 的一行，对应 stock_fund_flow（紧凑数组解码后）。 */
internal data class FundFlowRow(
    val tradeDate: String,
    val mainNet: Double,
    val mainRatio: Double,
    val superNet: Double?,
    val bigNet: Double?,
    val midNet: Double?,
    val smallNet: Double?,
    val source: String,
)

internal expect fun loadAssetText(path: String): String?
