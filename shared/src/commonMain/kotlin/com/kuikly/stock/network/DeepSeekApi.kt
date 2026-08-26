package com.kuikly.stock.network

import com.kuikly.stock.data.ChatResult
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.pages.AIAnalysisData
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockListItem
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 方案 B：App 直连 DeepSeek（OpenAI 兼容 API）
 *
 * - 行情/K线/指标等数据上下文由 [StockDb]（本地 SQLite）组装
 * - AI 调用走真实 DeepSeek 模型，API key 配在 [DeepSeekConfig]（见下，发布前自行替换）
 * - ⚠️ key 会随 APK 分发而暴露，仅适合个人 Demo；正式分发需自建中转
 */
object DeepSeekConfig {
    const val BASE_URL = "https://api.deepseek.com/v1"
    const val MODEL = "deepseek-chat"
    /** 发布前替换为你自己的 key（https://platform.deepseek.com/）
     * ⚠️ 真实 key 不入库（公共仓库会泄露）；本地构建前从 backend/.env 的 DEEPSEEK_API_KEY 复制进来再打包 */
    const val API_KEY = "sk-63d78e00e0db4a4ea39084affcde0d72"
    const val MAX_TOKENS = 4096
    const val TEMPERATURE = 0.7
    /** 未配置真实 key 时，AI 相关功能自动回退本地模板（避免无效网络请求） */
    val enabled: Boolean get() = API_KEY.isNotEmpty() && !API_KEY.startsWith("sk-xxxx")
}

private const val TAG_JSON = "```json"

object DeepSeekApi {

    /**
     * 调用 chat/completions，返回 assistant 文本
     * @param messages role→content 列表
     */
    suspend fun chat(messages: List<Pair<String, String>>): String {
        val body: JsonObject = buildJsonObject {
            put("model", DeepSeekConfig.MODEL)
            put("temperature", DeepSeekConfig.TEMPERATURE)
            put("max_tokens", DeepSeekConfig.MAX_TOKENS)
            put(
                "messages", buildJsonArray {
                    for ((role, content) in messages) {
                        add(buildJsonObject {
                            put("role", role)
                            put("content", content)
                        })
                    }
                }
            )
        }

        println("[DeepSeek] POST " + DeepSeekConfig.BASE_URL + "/chat/completions model=" + DeepSeekConfig.MODEL + " messages=" + messages.size)
        val resp = ApiClient.client.post("${DeepSeekConfig.BASE_URL}/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${DeepSeekConfig.API_KEY}")
            setBody(body.toString())
        }
        val text = resp.bodyAsText(Charsets.UTF_8)
        if (resp.status.value !in 200..299) {
            println("[DeepSeek] HTTP ERROR " + resp.status.value + ": " + text.take(160))
            throw Exception("AI 接口返回 HTTP " + resp.status.value + "（" + text.take(120) + "）")
        }
        println("[DeepSeek] response received: " + text.take(200))
        val root = ApiClient.json.parseToJsonElement(text).jsonObject
        val choices = root["choices"] as? JsonArray
            ?: throw Exception("AI 响应缺少 choices")
        val content = choices[0].jsonObject["message"]?.jsonObject?.get("content")
            ?: throw Exception("AI 响应缺少 content")
        return content.jsonPrimitive.content
    }

    // ==================== 个股分析（对应后端 prompts/stock_analysis.py） ====================

    suspend fun analyzeStock(detail: StockDetailData): AIAnalysisData {
        val messages = buildAnalysisPrompt(detail)
        val raw = chat(messages)
        val analysis = parseJsonObjectLoose(raw)
        val cards = buildAnalysisCards(detail, analysis)
        return AIAnalysisData(
            code = detail.info?.code ?: "",
            name = detail.info?.name,
            analysis = analysis.mapValues { it.value?.toString() ?: "" },
            cards = cards,
        )
    }

    private fun buildAnalysisPrompt(detail: StockDetailData): List<Pair<String, String>> {
        val info = detail.info
        val infoText = buildString {
            info?.let {
                append("\n- 代码：${it.code}")
                append("\n- 名称：${it.name ?: "未知"}")
                append("\n- 行业：${it.industry ?: "未知"}")
                append("\n- 板块：${it.plate ?: "未知"}")
            }
        }
        val r = detail.realtime
        val realtimeText = if (r != null) buildString {
            append("\n- 最新价：${r.price ?: "-"}")
            append("\n- 涨跌幅：${r.changePercent ?: "-"}%")
            append("\n- 开盘价：${r.openPrice ?: "-"}")
            append("\n- 最高价：${r.high ?: "-"}")
            append("\n- 最低价：${r.low ?: "-"}")
            append("\n- 成交量：${r.volume ?: "-"} 手")
            append("\n- 成交额：${r.amount ?: "-"} 元")
            append("\n- 市盈率(PE)：${r.peTtm ?: "-"}")
            append("\n- 市净率(PB)：${r.pb ?: "-"}")
        } else ""

        val kline = detail.kline.orEmpty()
        val klineText = if (kline.isNotEmpty()) {
            val recent = kline.takeLast(10)
            recent.joinToString("\n") { k ->
                "  ${k.tradeDate}: 开${k.open} 收${k.close} 高${k.high} 低${k.low} 量${k.volume}"
            }
        } else ""

        val ind: IndicatorData? = detail.indicator
        val indicatorText = if (ind != null) buildString {
            append("\n- 交易日：${ind.tradeDate}")
            append("\n- 均线：MA5=${ind.ma5}, MA10=${ind.ma10}, MA20=${ind.ma20}")
            append("\n- MACD：DIF=${ind.dif}, DEA=${ind.dea}, MACD柱=${ind.macd}")
            append("\n- RSI(6)：${ind.rsi6}")
            append("\n- KDJ：K=${ind.kdjK}, D=${ind.kdjD}, J=${ind.kdjJ}")
        } else ""

        val system = """你是一位专业的股票分析师，请对以下股票进行全面的综合分析。

请从以下维度进行分析，并以 JSON 格式返回：
1. 趋势判断 (trend): 短期/中期/长期趋势如何？用一句话描述
2. 技术信号 (signals): 是否有明显的买入/卖出信号？列出所有发现的信号（如均线金叉/死叉、MACD形态等）
3. 支撑压力位 (support_price, resistance_price): 关键的支撑位和压力位在哪里？
4. 风险评估 (risk_level, risks): 当前风险等级（低/中/高）？需要注意哪些风险因素？
5. 操作建议 (suggestion): 买入/卖出/持有/观望？目标价位和止损位？
6. 总结 (summary): 一句话总结当前该股的投资价值和风险

JSON 格式示例：
{
  "trend": "趋势描述",
  "signals": ["信号1", "信号2"],
  "support_price": "支撑位价格",
  "resistance_price": "压力位价格",
  "risk_level": "低|中|高",
  "risks": ["风险因素1", "风险因素2"],
  "suggestion": "买入|卖出|持有|观望",
  "target_price": "目标价位",
  "stop_loss": "止损价位",
  "summary": "一句话总结"
}"""

        val user = """请分析以下股票：

## 股票基础信息
$infoText

## 实时行情
$realtimeText

## 近期K线数据（最近10个交易日）
$klineText

## 最新技术指标（程序计算，非AI生成）
$indicatorText

请根据以上数据进行全面分析，需结合技术指标（均线排列、MACD金叉死叉、RSI超买超卖、KDJ钝化等）给出专业判断。"""

        return listOf("system" to system, "user" to user)
    }

    private fun buildAnalysisCards(detail: StockDetailData, a: Map<String, Any?>): List<Map<String, Any?>> {
        val cards = mutableListOf<Map<String, Any?>>()
        val code = detail.info?.code ?: ""

        a["trend"]?.let {
            val sug = a["suggestion"]?.toString()
            val color = if (sug == "买入" || sug == "持有") "#FF6B6B" else "#4ECDC4"
            cards.add(mapOf("type" to "trend_card", "title" to "趋势判断",
                "content" to it.toString(), "color" to color))
        }
        (a["signals"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let {
            cards.add(mapOf("type" to "signal_card", "title" to "技术信号",
                "signals" to it.map { x -> x?.toString() ?: "" }, "color" to "#45B7D1"))
        }
        a["suggestion"]?.let {
            val color = if (it.toString() == "买入" || it.toString() == "持有") "#96CEB4" else "#FF6B6B"
            cards.add(mapOf("type" to "suggestion_card", "title" to "操作建议",
                "suggestion" to it.toString(),
                "target_price" to (a["target_price"]?.toString() ?: "-"),
                "stop_loss" to (a["stop_loss"]?.toString() ?: "-"),
                "support_price" to (a["support_price"]?.toString() ?: "-"),
                "resistance_price" to (a["resistance_price"]?.toString() ?: "-"),
                "color" to color))
        }
        a["risk_level"]?.let {
            val risks = (a["risks"] as? List<*>)?.map { x -> x?.toString() ?: "" } ?: emptyList()
            val colorMap = mapOf("低" to "#96CEB4", "中" to "#FFEAA7", "高" to "#FF6B6B")
            cards.add(mapOf("type" to "risk_card", "title" to "风险评估",
                "risk_level" to it.toString(), "risks" to risks,
                "color" to (colorMap[it.toString()] ?: "#FFEAA7")))
        }
        a["summary"]?.let {
            cards.add(mapOf("type" to "summary_card", "title" to "AI 总结",
                "summary" to it.toString(), "color" to "#DDA0DD"))
        }
        // 数据来源标注
        cards.add(mapOf("type" to "summary_card", "title" to "数据来源",
            "summary" to "技术指标来自本地 SQLite（真实计算值），AI 由 DeepSeek 直连生成。",
            "color" to "#90A4AE"))
        return cards
    }

    // ==================== 智能问答（对应后端 prompts/chat.py） ====================

    suspend fun chat(
        message: String,
        mentioned: List<StockListItem>,
        buildContext: () -> String,
    ): ChatResult {
        val context = buildContext()
        val messages = buildChatPrompt(message, context)
        val raw = chat(messages)
        return parseChatReply(raw, mentioned)
    }

    private fun buildChatPrompt(message: String, context: String): List<Pair<String, String>> {
        val system = """你是一位专业的投资顾问AI助手，擅长股票市场分析和投资建议。

你的特点：
1. 专业但不晦涩，通俗易懂地解释金融概念
2. 数据准确，只引用「相关股票数据」中给出的具体数字，绝不编造数据
3. 给出明确观点和建议，不模棱两可
4. 风险意识强，会提醒投资者注意风险
5. 回答简洁有力，避免冗长

排版要求：
- 使用 Markdown 格式组织回答，层次清晰、便于移动端阅读
- 用 ## 或 ### 小标题分节
- 重点内容用 **加粗** 强调
- 列举项目用 - 列表
- 全文中文字数控制在 200-400 字

回复格式（JSON，卡片字段一律用 snake_case）：
{
  "text": "Markdown格式的详细回答文本",
  "cards": [
    { "type": "stock_card", "code": "股票代码", "name": "股票名称", "price": "最新价", "change_percent": "涨跌幅，如 +2.35%" }
  ],
  "suggestions": ["推荐的后续问题1", "推荐的后续问题2"]
}

注意：cards 可选；字段名必须用 change_percent / chart_type；suggestions 给 2-3 个；相关股票数据为空时如实说明，不要编造。"""

        val user = if (context.isBlank()) {
            "用户问题：$message\n\n请根据以上信息给出专业、准确的回答。"
        } else {
            """用户问题：$message

相关股票数据：
$context

请根据以上信息给出专业、准确的回答。"""
        }
        return listOf("system" to system, "user" to user)
    }

    private fun parseChatReply(raw: String, mentioned: List<StockListItem>): ChatResult {
        var text = raw
        var cards: List<Map<String, Any?>>? = null
        var suggestions: List<String>? = null

        val obj = parseJsonObjectLoose(raw)
        if (obj.isNotEmpty()) {
            obj["text"]?.let { text = it.toString() }
            (obj["cards"] as? List<*>)?.let {
                cards = it.mapNotNull { c ->
                    @Suppress("UNCHECKED_CAST")
                    val m = c as? Map<String, Any?> ?: return@mapNotNull null
                    normalizeCard(m)
                }.ifEmpty { null }
            }
            (obj["suggestions"] as? List<*>)?.let {
                suggestions = it.map { x -> x?.toString() ?: "" }.ifEmpty { null }
            }
        }

        // 未提取到卡片但提到了股票 → 补股票卡片（用本地真实行情）
        if (cards == null && mentioned.isNotEmpty()) {
            val auto = mutableListOf<Map<String, Any?>>()
            for (s in mentioned.take(2)) {
                auto.add(mapOf(
                    "type" to "stock_card",
                    "code" to s.code,
                    "name" to (s.name ?: s.code),
                    "price" to (s.price?.let { "%.2f".format(it) } ?: "-"),
                    "change_percent" to (s.changePercent?.let { "%+.2f%%".format(it) } ?: "-"),
                ))
            }
            cards = auto
        }
        if (suggestions == null) {
            suggestions = listOf("查看技术指标分析？", "对比同行业表现？", "了解最新市场动态？")
        }
        return ChatResult(text = text, cards = cards, suggestions = suggestions)
    }

    private fun normalizeCard(m: Map<String, Any?>): Map<String, Any?> {
        // changePercent -> change_percent, chartType -> chart_type
        val mapping = mapOf("changePercent" to "change_percent", "chartType" to "chart_type")
        return m.mapKeys { (k, _) -> mapping[k] ?: k }
    }

    // ==================== 上下文构建（本地 SQLite 数据） ====================

    /**
     * 组装 AI 问答上下文：命中股票的最新行情 + 近5日K线 + 技术指标 + 市场概览。
     * 数据全部来自 [StockDb]（本地 SQLite）。
     */
    fun buildChatContext(message: String, mentioned: List<StockListItem>): String {
        if (mentioned.isEmpty()) return ""
        val parts = mutableListOf<String>()
        for (s in mentioned) {
            val detail = try { StockDb.stockDetail(s.code) } catch (e: Throwable) { null } ?: continue
            val r: RealtimeQuoteData = detail.realtime ?: continue
            parts.add(
                "- ${r.name ?: s.code}(${s.code}): 最新价 ${r.price}, 涨跌幅 ${r.changePercent}%, " +
                    "开盘 ${r.openPrice}, 最高 ${r.high}, 最低 ${r.low}, " +
                    "成交量 ${r.volume} 手, 成交额 ${r.amount} 元, PE(TTM) ${r.peTtm}, PB ${r.pb}"
            )
            val kline = detail.kline.orEmpty()
            if (kline.isNotEmpty()) {
                val recent = kline.takeLast(5)
                parts.add("  近${recent.size}日K线: " + recent.joinToString(", ") {
                    "${it.tradeDate} 开${it.open} 收${it.close} 高${it.high} 低${it.low}"
                })
            }
            detail.indicator?.let { ind ->
                parts.add(
                    "  技术指标(${ind.tradeDate}): MA5=${fmt(ind.ma5)}, MA10=${fmt(ind.ma10)}, MA20=${fmt(ind.ma20)}, " +
                        "MACD(DIF=${fmt(ind.dif)}/DEA=${fmt(ind.dea)}/柱=${fmt(ind.macd)}), " +
                        "RSI6=${fmt(ind.rsi6)}, KDJ(K=${fmt(ind.kdjK)}/D=${fmt(ind.kdjD)}/J=${fmt(ind.kdjJ)})"
                )
            }
        }
        if (isMarketQuestion(message)) {
            val ov = try { StockDb.marketOverview() } catch (e: Throwable) { null }
            if (ov != null) {
                parts.add("- 全市场概览: 共 ${ov.total} 只, 上涨 ${ov.up} 家, 下跌 ${ov.down} 家, 平盘 ${ov.flat} 家")
                parts.add("- 涨幅榜: " + ov.topGainers.joinToString(", ") { "${it.name ?: it.code}(+${it.changePercent}%)" })
                parts.add("- 跌幅榜: " + ov.topLosers.joinToString(", ") { "${it.name ?: it.code}(${it.changePercent}%)" })
            }
        }
        return parts.joinToString("\n")
    }

    private fun isMarketQuestion(message: String): Boolean {
        val keywords = listOf("大盘", "行情", "市场", "指数", "涨幅榜", "跌幅榜", "涨跌", "板块", "整体")
        return keywords.any { message.contains(it) }
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else "%.3f".format(v)

    // ==================== JSON 解析工具 ====================

    /** 从 AI 文本里宽松提取 JSON 对象（容忍 ```json 代码块 / 纯 JSON / 夹杂文字） */
    private fun parseJsonObjectLoose(raw: String): Map<String, Any?> {
        val jsonStr = extractJsonBlock(raw) ?: return emptyMap()
        return try {
            ApiClient.json.parseToJsonElement(jsonStr).jsonObject.mapValues { it.value.toAnyDeep() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun extractJsonBlock(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            TAG_JSON in trimmed -> {
                val start = trimmed.indexOf(TAG_JSON) + TAG_JSON.length
                val end = trimmed.indexOf("```", start)
                if (end > start) trimmed.substring(start, end).trim() else trimmed.substring(start).trim()
            }
            trimmed.startsWith("{") -> trimmed.substringBeforeLast("}") + "}"
            "```" in trimmed -> {
                val start = trimmed.indexOf("```") + 3
                val end = trimmed.indexOf("```", start)
                if (end > start) trimmed.substring(start, end).trim() else null
            }
            else -> null
        }
    }
}

/** kotlinx JsonElement -> Map/List/scalar（用于 analysis/chat 解析后的自由取值） */
private fun kotlinx.serialization.json.JsonElement.toAnyDeep(): Any? = when (this) {
    is JsonPrimitive -> {
        if (isString) content
        else content.toDoubleOrNull() ?: when (content) {
            "true" -> true; "false" -> false; else -> content
        }
    }
    is JsonArray -> map { it.toAnyDeep() }
    is JsonObject -> mapValues { it.value.toAnyDeep() }
}
