package com.kuikly.stock.data

import com.kuikly.stock.pages.StockListItem
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockInfoData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.KLineDataItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 本地数据服务（离线优先）
 *
 * 从 assets 读取预置的 JSON 数据：
 * - stock_list.json: 股票列表（含模拟行情价格）
 * - stock_kline.json: 前50只股票的近30日K线
 *
 * 所有方法同步执行，不依赖网络。
 * KMP 项目通过 expect/actual 实现跨平台文件读取。
 */
object LocalDataService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 缓存：股票列表 */
    private var cachedStockList: List<StockListItem>? = null

    /** 缓存：K线原始数据 Map<code, List<KLineRaw>> */
    private var cachedKlines: Map<String, List<KLineRaw>>? = null

    /**
     * 从 assets 加载全部股票列表（带模拟行情）
     * 返回 StockListItem 列表，可直接用于 StockListPage 展示
     */
    fun loadStockList(): List<StockListItem> {
        cachedStockList?.let { return it }
        val raw = loadAssetText("stock_list.json") ?: return emptyList()
        val arr = json.parseToJsonElement(raw).jsonArray
        val list = arr.map { elem ->
            val obj = elem.jsonObject
            StockListItem(
                code = obj["code"]!!.jsonPrimitive.content,
                name = obj["name"]?.jsonPrimitive?.content,
                price = obj["_mock_price"]?.jsonPrimitive?.doubleOrNull,
                changePercent = obj["_mock_change_percent"]?.jsonPrimitive?.doubleOrNull
            )
        }
        cachedStockList = list
        return list
    }

    /**
     * 搜索股票（按代码或名称模糊匹配）
     */
    fun searchStocks(keyword: String): List<StockListItem> {
        if (keyword.isBlank()) return loadStockList()
        val k = keyword.trim().uppercase()
        return loadStockList().filter {
            it.code.contains(k) || (it.name?.uppercase()?.contains(k) == true)
        }
    }

    /**
     * 加载个股详情（基础信息 + 模拟实时行情 + K线）
     */
    fun loadStockDetail(code: String): StockDetailData? {
        // 1. 从列表中找基础信息 + 价格
        val stock = loadStockList().find { it.code == code } ?: return null

        // 2. 构造模拟实时行情
        val price = stock.price ?: 10.0
        val changePct = stock.changePercent ?: 0.0
        val change = price * changePct / 100.0

        val realtime = RealtimeQuoteData(
            code = code,
            name = stock.name,
            price = price,
            change = round2(change),
            changePercent = round2(changePct),
            openPrice = round2(price * randomFactor(0.97, 1.03)),
            preClose = round2(price / (1 + changePct / 100.0)),
            high = round2(price * randomFactor(1.0, 1.05)),
            low = round2(price * randomFactor(0.95, 1.0)),
            volume = randomDouble(10000.0, 500000.0),
            amount = randomDouble(8000000.0, 200000000.0),
            peTtm = randomDouble(5.0, 80.0),
            pb = randomDouble(0.5, 10.0)
        )

        // 3. 加载K线数据
        val klineMap = loadAllKlines()
        val klineItems = klineMap[code]?.map {
            KLineDataItem(code = it.code, tradeDate = it.tradeDate,
                open = it.open, close = it.close, high = it.high,
                low = it.low, volume = it.volume, amount = it.amount)
        } ?: emptyList()

        // 4. 基础信息（从原始JSON取完整字段）
        val raw = loadAssetText("stock_list.json") ?: return null
        val arr = json.parseToJsonElement(raw).jsonArray
        val infoObj = arr.firstOrNull {
            it.jsonObject["code"]?.jsonPrimitive?.content == code
        }?.jsonObject ?: return null

        val info = StockInfoData(
            code = infoObj["code"]!!.jsonPrimitive.content,
            name = infoObj["name"]?.jsonPrimitive?.content,
            industry = infoObj["industry"]?.jsonPrimitive?.content,
            plate = infoObj["plate"]?.jsonPrimitive?.content,
            listDate = infoObj["list_date"]?.jsonPrimitive?.content
        )

        return StockDetailData(info = info, realtime = realtime, kline = klineItems)
    }

    /**
     * 加载全部K线数据到内存
     */
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

    // ==================== 离线 AI 问答（Mock） ====================

    /**
     * 离线 Mock 问答：基于本地股票数据做关键词驱动回答。
     * 返回文本 + 结构化卡片 + 追问建议，供 ChatMainPage 渲染。
     */
    fun mockChat(message: String): ChatResult {
        val msg = message.trim()
        if (msg.isEmpty()) return generalHelp()

        // 1. 命中 6 位股票代码
        val codeHit = Regex("\\d{6}").find(msg)?.value?.let { code ->
            loadStockList().find { it.code == code }
        }

        // 2. 命中股票名称
        val nameHit = if (codeHit == null) {
            loadStockList().firstOrNull { stock ->
                stock.name?.let { name -> msg.contains(name) } == true
            }
        } else null

        val target = codeHit ?: nameHit

        return when {
            target != null -> stockQa(target)
            isMarketQuestion(msg) -> marketOverview()
            else -> generalHelp()
        }
    }

    /** 是否属于大盘/行情类问题 */
    private fun isMarketQuestion(msg: String): Boolean {
        val keywords = listOf("大盘", "行情", "市场", "指数", "涨幅榜", "跌幅榜", "涨跌", "板块")
        return keywords.any { msg.contains(it) }
    }

    /** 个股解读问答 */
    private fun stockQa(stock: StockListItem): ChatResult {
        val code = stock.code
        val name = stock.name ?: code
        val detail = loadStockDetail(code)
        val price = detail?.realtime?.price ?: stock.price ?: 0.0
        val changePct = detail?.realtime?.changePercent ?: stock.changePercent ?: 0.0
        val kline = detail?.kline ?: emptyList()

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

        val text = "$name($code) 最新价 ${fmt2(price)} 元，涨跌幅 ${fmt2(changePct)}%。" +
            "当前处于$trend 阶段，综合技术信号建议：$suggestion。"

        val cards = listOf<Map<String, Any?>>(
            mapOf(
                "type" to "stock_card",
                "code" to code,
                "name" to name,
                "price" to fmt2(price),
                "changePercent" to (if (changePct >= 0) "+" else "") + fmt2(changePct) + "%"
            ),
            mapOf("type" to "trend_card", "content" to "$name 当前处于$trend 阶段，建议关注成交量变化与均线支撑。"),
            mapOf("type" to "signal_card", "content" to signals.joinToString("；")),
            mapOf("type" to "suggestion_card", "content" to "操作建议：$suggestion（目标价 ${fmt2(price * 1.05)}，止损 ${fmt2(price * 0.95)}）")
        )

        val suggestions = listOf(
            "${name}最近走势如何？",
            "帮我看看$code 的技术信号",
            "今天大盘怎么样？"
        )

        return ChatResult(text = text, cards = cards, suggestions = suggestions)
    }

    /** 大盘/行情概览问答 */
    private fun marketOverview(): ChatResult {
        val all = loadStockList()
        val up = all.count { (it.changePercent ?: 0.0) > 0 }
        val down = all.count { (it.changePercent ?: 0.0) < 0 }
        val flat = all.size - up - down

        val topGainers = all.sortedByDescending { it.changePercent ?: 0.0 }.take(3)
        val topLosers = all.sortedBy { it.changePercent ?: 0.0 }.take(3)

        val text = "今日全市场共 ${all.size} 只股票，上涨 $up 家、下跌 $down 家、平盘 $flat 家。" +
            "涨幅榜前列：${topGainers.joinToString("、") { "${it.name}(+${fmt2(it.changePercent ?: 0.0)}%)" }}；" +
            "跌幅榜前列：${topLosers.joinToString("、") { "${it.name}(${fmt2(it.changePercent ?: 0.0)}%)" }}。"

        val cards = mutableListOf<Map<String, Any?>>(
            mapOf("type" to "chart_card", "title" to "市场概览", "chartType" to "bar")
        )
        topGainers.forEach { cards.add(stockCardOf(it)) }
        topLosers.forEach { cards.add(stockCardOf(it)) }

        val suggestions = listOf("涨幅榜有哪些？", "跌幅榜有哪些？", "帮我分析 600519")

        return ChatResult(text = text, cards = cards, suggestions = suggestions)
    }

    /** 兜底帮助回复 */
    private fun generalHelp(): ChatResult {
        val text = "我是 AI 股票助手，可以帮你查询个股行情、解读趋势并给出操作参考。" +
            "试试输入股票代码或名称，例如「帮我分析 600519」或「贵州茅台怎么样」。"
        val suggestions = listOf("帮我分析 600519", "今天大盘怎么样？", "涨幅榜有哪些？", "贵州茅台怎么样？")
        return ChatResult(text = text, cards = null, suggestions = suggestions)
    }

    /** 构造股票卡片（供聊天回复使用） */
    private fun stockCardOf(s: StockListItem): Map<String, Any?> = mapOf(
        "type" to "stock_card",
        "code" to s.code,
        "name" to (s.name ?: s.code),
        "price" to fmt2(s.price ?: 0.0),
        "changePercent" to (if ((s.changePercent ?: 0.0) >= 0) "+" else "") + fmt2(s.changePercent ?: 0.0) + "%"
    )

    // ==================== 工具函数 ====================

    private fun round2(v: Double) = kotlin.math.round(v * 100.0) / 100.0
    private fun fmt2(v: Double): String = String.format("%.2f", v)
    private fun randomFactor(lo: Double, hi: Double) = kotlin.random.Random.nextDouble() * (hi - lo) + lo
    private fun randomDouble(lo: Double, hi: Double) = kotlin.random.Random.nextDouble() * (hi - lo) + lo

    /** K线原始数据结构 */
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

/**
 * AI 问答回复（离线 Mock 与在线后端统一使用此结构）
 */
data class ChatResult(
    val text: String,
    val cards: List<Map<String, Any?>>? = null,
    val suggestions: List<String>? = null
)

// ==================== 平台相关（顶层 expect/actual） ====================

/**
 * expect/actual: 读取 assets 文件内容（跨平台）
 * 各平台在 androidMain / iosMain / jsMain 中 actual 实现
 */
internal expect fun loadAssetText(path: String): String?
