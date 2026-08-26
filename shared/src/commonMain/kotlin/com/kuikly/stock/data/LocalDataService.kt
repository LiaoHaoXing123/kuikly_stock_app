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
        // 1. 从原始 JSON 取完整字段（assets 已升级为真实数据，含真实行情字段）
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

        // 2. 构造实时行情：优先用 assets 中的真实行情字段，缺失时用随机值兜底
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

        // 3. 加载K线数据
        val klineMap = loadAllKlines()
        val klineItems = klineMap[code]?.map {
            KLineDataItem(code = it.code, tradeDate = it.tradeDate,
                open = it.open, close = it.close, high = it.high,
                low = it.low, volume = it.volume, amount = it.amount)
        } ?: emptyList()

        // 4. 基础信息
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

    /** 个股解读问答（离线/回退模式：不输出实时价格、涨跌幅与买卖点，仅基本面参考） */
    private fun stockQa(stock: StockListItem): ChatResult {
        val code = stock.code
        val name = stock.name ?: code
        val detail = loadStockDetail(code)
        val info = detail?.info
        val industry = info?.industry ?: "未知"
        val plate = info?.plate ?: "未知"
        val listDate = info?.listDate ?: "未知"

        // 与在线 DeepSeek 输出保持一致的 Markdown 排版（## 标题 / - 列表 / **加粗**）
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

        // 离线/回退模式快捷按钮：用基本面/风险类问题（行情类问题离线拿不到实时数据）
        val suggestions = listOf(
            "$name 的基本面怎么样？",
            "$name 有哪些投资风险？",
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

        // 离线模式：不输出实时涨跌幅/价格，仅本地样本统计参考（与在线 Markdown 同风格）
        val text = "## 今日市场概览\n\n" +
            "**离线模式**：无法获取实时涨跌数据，以下为本地样本统计参考：\n\n" +
            "- 样本股票：共 **${all.size}** 只\n" +
            "- 上涨（本地样本）：**$up** 家\n" +
            "- 下跌（本地样本）：**$down** 家\n\n" +
            "离线模式不提供实时价格与涨跌幅，请切换在线模式获取真实行情。"

        // 离线不展示带价格的股票卡片，仅保留图表占位
        val cards = mutableListOf<Map<String, Any?>>(
            mapOf("type" to "chart_card", "title" to "市场概览", "chartType" to "bar")
        )

        val suggestions = listOf("涨幅榜有哪些？", "跌幅榜有哪些？", "帮我分析 600519")

        return ChatResult(text = text, cards = cards, suggestions = suggestions)
    }

    /** 兜底帮助回复（Markdown 排版，与在线回复样式一致） */
    private fun generalHelp(): ChatResult {
        val text = "我是 **AI 股票助手**，可以帮你查询个股行情、解读趋势并给出操作参考。\n\n" +
            "试试输入股票代码或名称：\n" +
            "- 「帮我分析 600519」\n" +
            "- 「贵州茅台怎么样」"
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

    /**
     * 离线模拟个股分析（真实 AI 不可达时的兜底）
     * 基于本地数据生成分析卡片，并明确标注"未能连接 AI 服务"
     */
    suspend fun mockAnalysis(code: String): AIAnalysisData? {
        val detail = loadStockDetail(code) ?: return null
        // 注意：不在这里加 delay —— Kuikly 的 delay 是 CoroutineScope 扩展，普通 suspend 函数无 receiver；
        // 且 kotlinx delay 与 Kuikly 协程混用会改变恢复线程（已踩坑），mock 数据无需模拟延迟。

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
                    "color" to "#E53935"
                ),
                mapOf(
                    "type" to "suggestion_card", "title" to "操作建议",
                    "suggestion" to suggestion,
                    "target_price" to fmt2(price * 1.05),
                    "stop_loss" to fmt2(price * 0.95),
                    "color" to "#43A047"
                ),
                mapOf(
                    "type" to "summary_card", "title" to "总结",
                    "content" to "$name($code): $trend | ${signals.size} 项技术信号 | 模板回答",
                    "color" to "#7B1FA2"
                )
            )
        )
    }

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
 * @param errorNotice 可选：AI 服务不可用时给用户看的简短提示（由页面以悬浮提示展示，不塞进聊天气泡）
 */
data class ChatResult(
    val text: String,
    val cards: List<Map<String, Any?>>? = null,
    val suggestions: List<String>? = null,
    val errorNotice: String? = null
)

// ==================== 平台相关（顶层 expect/actual） ====================

/**
 * expect/actual: 读取 assets 文件内容（跨平台）
 * 各平台在 androidMain / iosMain / jsMain 中 actual 实现
 */
internal expect fun loadAssetText(path: String): String?
