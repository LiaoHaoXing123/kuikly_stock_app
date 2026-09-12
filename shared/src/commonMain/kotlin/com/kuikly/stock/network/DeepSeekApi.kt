// DeepSeek 接口封装。行情数据从本地 SQLite 组装进提示词，模型只负责分析生成。

package com.kuikly.stock.network

import com.kuikly.stock.ai.chat.*
import com.kuikly.stock.ai.config.DeepSeekConfig
import com.kuikly.stock.ai.config.AiConnectionResult
import com.kuikly.stock.ai.config.AiProviderException
import com.kuikly.stock.ai.config.AiProviderProfile
import com.kuikly.stock.ai.config.AiRequestConfig
import com.kuikly.stock.ai.config.AiRuntimeConfig
import com.kuikly.stock.ai.config.providerErrorMessage
import com.kuikly.stock.ai.config.resolveAiRequestConfig
import com.kuikly.stock.ai.prompt.ChatPromptContext
import com.kuikly.stock.ai.protocol.DetailProtocolV2
import com.kuikly.stock.ai.protocol.ValidateReport
import com.kuikly.stock.ai.protocol.VerdictSynthesizer
import com.kuikly.stock.ai.protocol.anyToStringMap
import com.kuikly.stock.ai.protocol.validateCards
import com.kuikly.stock.ai.protocol.validateObject
import com.kuikly.stock.ai.tool.StockTools
import com.kuikly.stock.util.normalizeBreaks
import com.kuikly.stock.data.AIVerdict
import com.kuikly.stock.data.ChatResult
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.pages.AIAnalysisData
import com.kuikly.stock.pages.IndicatorData
import com.kuikly.stock.pages.RealtimeQuoteData
import com.kuikly.stock.pages.StockDetailData
import com.kuikly.stock.pages.StockListItem
import com.kuikly.stock.pages.fmtMoney
import com.kuikly.stock.pages.validatedAnalysisEvidence
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
import com.kuikly.stock.data.nowMillis
import com.kuikly.stock.data.fmt3

private const val TAG_JSON = "```json"

object DeepSeekApi {

    suspend fun chat(messages: List<Pair<String, String>>): String {
        val config = AiRuntimeConfig.current()
        return extractContent(postChat(config, chatBody(config, messages, null)))
            ?: throw Exception("AI 响应缺少 content")
    }

    suspend fun chatWithTools(messages: List<Pair<String, String>>): String {
        val config = AiRuntimeConfig.current()
        if (!config.toolsEnabled) {
            return extractContent(postChat(config, chatBody(config, messages, null)))
                ?: throw Exception("AI 响应缺少 content")
        }
        val msg = postChat(config, chatBody(config, messages, StockTools.definitions))
        val toolCalls = (msg["tool_calls"] as? JsonArray).orEmpty()
        if (toolCalls.isEmpty()) {
            return extractContent(msg) ?: throw Exception("AI 响应缺少 content")
        }
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", DeepSeekConfig.TEMPERATURE)
            put("max_tokens", DeepSeekConfig.MAX_TOKENS)
            put("messages", buildJsonArray {
                for ((role, content) in messages) add(buildJsonObject { put("role", role); put("content", content) })
                add(msg)
                for (tc in toolCalls) {
                    val tcObj = tc as? JsonObject ?: continue
                    val f = tcObj["function"]?.jsonObject ?: continue
                    val name = f["name"]?.jsonPrimitive?.content ?: ""
                    val argsRaw = f["arguments"]?.jsonPrimitive?.content ?: "{}"
                    val args = try {
                        ApiClient.json.parseToJsonElement(argsRaw).jsonObject
                    } catch (_: Throwable) { buildJsonObject { } }
                    val result = StockTools.execute(name, args)
                    add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", tcObj["id"]?.jsonPrimitive?.content ?: "")
                        put("name", name)
                        put("content", result.toString())
                    })
                }
            })
        }
        return extractContent(postChat(config, body))
            ?: throw Exception("AI 响应缺少 content")
    }

    private fun chatBody(
        config: AiRequestConfig,
        messages: List<Pair<String, String>>,
        tools: JsonArray?,
        maxTokens: Int = DeepSeekConfig.MAX_TOKENS,
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("temperature", DeepSeekConfig.TEMPERATURE)
        put("max_tokens", maxTokens)
        put("messages", buildJsonArray {
            for ((role, content) in messages) {
                add(buildJsonObject { put("role", role); put("content", content) })
            }
        })
        if (tools != null) put("tools", tools)
    }

    private suspend fun postChat(config: AiRequestConfig, body: JsonObject): JsonObject {
        val startedAt = nowMillis()
        val resp = ApiClient.client.post(config.endpoint) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${config.apiKey}")
            setBody(body.toString())
        }
        val text = resp.bodyAsText()
        val elapsedMs = nowMillis() - startedAt
        if (resp.status.value !in 200..299) {
            println("[AI] provider=${config.providerName} model=${config.model} status=${resp.status.value} elapsedMs=$elapsedMs")
            throw AiProviderException(resp.status.value, providerErrorMessage(resp.status.value))
        }
        println("[AI] provider=${config.providerName} model=${config.model} status=${resp.status.value} elapsedMs=$elapsedMs")
        val root = ApiClient.json.parseToJsonElement(text).jsonObject
        val choices = root["choices"] as? JsonArray
            ?: throw Exception("AI 响应缺少 choices")
        return choices[0].jsonObject["message"]?.jsonObject
            ?: throw Exception("AI 响应缺少 message")
    }

    private fun extractContent(m: JsonObject): String? = m["content"]?.jsonPrimitive?.content

    internal suspend fun testConnection(profile: AiProviderProfile, apiKey: String): AiConnectionResult {
        val startedAt = nowMillis()
        return try {
            val config = resolveAiRequestConfig(profile, apiKey)
            val body = chatBody(
                config = config,
                messages = listOf("user" to "只回复 OK"),
                tools = null,
                maxTokens = 8,
            )
            val message = postChat(config, body)
            if (extractContent(message).isNullOrBlank()) {
                throw IllegalStateException("服务响应格式不兼容")
            }
            AiConnectionResult(
                success = true,
                providerName = profile.name,
                model = profile.model,
                elapsedMs = nowMillis() - startedAt,
                message = "连接成功",
            )
        } catch (error: Throwable) {
            AiConnectionResult(
                success = false,
                providerName = profile.name,
                model = profile.model,
                elapsedMs = nowMillis() - startedAt,
                message = error.message ?: "无法连接 AI 服务",
            )
        }
    }

    suspend fun analyzeStock(detail: StockDetailData): AIAnalysisData {
        val messages = buildAnalysisPrompt(detail)
        val raw = chat(messages)
        val klineDates = detail.kline.orEmpty().map { it.tradeDate }.toSet()
        val generatedAt = nowMillis()
        val dataDate = detail.kline?.lastOrNull()?.tradeDate ?: detail.indicator?.tradeDate.orEmpty()

        // ---------- L1: 严格 v2 ----------
        parseAnalysisV2(raw, detail, klineDates, generatedAt, dataDate)?.let {
            println("[AI] v2 ok, degraded=${it.degraded}, note=${it.validateNote}")
            return it
        }

        // ---------- L2: 旧宽松协议 ----------
        runCatching { parseLegacyAnalysis(raw, detail, generatedAt, dataDate) }
            .getOrNull()
            ?.let { legacy ->
                println("[AI] fallback to legacy")
                val synth = VerdictSynthesizer.fromLegacy(legacy.analysis) { numericLevel(it) }
                return legacy.copy(
                    verdict = AIVerdict.fromSynth(synth),
                    protocolVersion = 1,
                    degraded = true,
                    validateNote = "AI 返回格式偏离协议 v2，已降级解析",
                )
            }

        // ---------- L3: 抛出异常让 StockRepository 走离线模板 ----------
        throw IllegalStateException("AI 返回的分析不完整，请重试")
    }

    private fun parseLegacyAnalysis(
        raw: String, detail: StockDetailData, generatedAt: Long, dataDate: String,
    ): AIAnalysisData {
        val analysis = parseJsonObjectLoose(raw)
        require(analysis["trend"] is String || analysis["summary"] is String) { "legacy: missing trend/summary" }
        val cards = buildAnalysisCards(detail, analysis)
        return AIAnalysisData(
            code = detail.info?.code ?: "",
            name = detail.info?.name,
            analysis = analysis.mapValues { it.value?.toString() ?: "" },
            cards = cards,
            generatedAt = generatedAt,
            dataDate = dataDate,
        )
    }

    private fun parseAnalysisV2(
        raw: String,
        detail: StockDetailData,
        klineDates: Set<String>,
        generatedAt: Long,
        dataDate: String,
    ): AIAnalysisData? {
        val root = try {
            strictJson.parseToJsonElement(stripCodeFence(raw)).toNativeValue() as? Map<String, Any?>
        } catch (_: Exception) { null } ?: return null
        if ((root["version"] as? Number)?.toInt() != DetailProtocolV2.VERSION) return null

        val report = ValidateReport()

        val verdictMap = anyToStringMap(root["verdict"]) ?: return null
        val verdictBody = validateObject(
            verdictMap,
            DetailProtocolV2.VERDICT_REQUIRED,
            DetailProtocolV2.VERDICT_OPTIONAL,
            strippedOut = report.strippedFields,
            prefix = "verdict.",
        ) ?: return null
        val verdict = AIVerdict.from(verdictBody)

        var cards = validateCards(
            raw = root["cards"] as? List<Any?>,
            registry = DetailProtocolV2.registry(klineDates),
            report = report,
            maxCards = DetailProtocolV2.MAX_CARDS,
        )
        // 同类型只保留第一张
        cards = cards.distinctBy { it["type"] }

        if (cards.isEmpty()) return null

        // 保留固定的"数据来源"卡
        cards = cards + sourceFooterCard(dataDate)

        // 兼容：analysis 字段仍填充，供历史记录/搜索等旧逻辑使用
        val flat = linkedMapOf<String, Any?>(
            "trend" to (cards.firstOrNull { it["type"] == "trend_card" }?.get("content")),
            "summary" to (cards.firstOrNull { it["type"] == "summary_card" }?.get("summary")),
            "suggestion" to (cards.firstOrNull { it["type"] == "level_card" }?.get("action")),
            "risk_level" to (cards.firstOrNull { it["type"] == "risk_card" }?.get("risk_level")),
            "support_price" to verdict.supportValue?.toString(),
            "resistance_price" to verdict.resistanceValue?.toString(),
            "target_price" to verdict.targetValue?.toString(),
            "stop_loss" to verdict.stopLossValue?.toString(),
        )

        return AIAnalysisData(
            code = detail.info?.code ?: "",
            name = detail.info?.name,
            analysis = flat,
            cards = cards,
            source = "DeepSeek",
            generatedAt = generatedAt,
            dataDate = dataDate,
            verdict = verdict,
            protocolVersion = 2,
            degraded = report.hasIssue(),
            validateNote = report.toString(),
        )
    }

    private fun stripCodeFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun sourceFooterCard(dataDate: String): Map<String, Any?> = mapOf(
        "type" to "summary_card",
        "summary" to "数据来源：真实行情 API；AI 结论仅供参考，不构成投资建议。数据日期 $dataDate",
        "footer" to true,
    )

    suspend fun analyzeIndex(detail: StockDetailData): AIAnalysisData {
        val messages = buildIndexAnalysisPrompt(detail)
        val raw = chat(messages)
        val analysis = parseJsonObjectLoose(raw)
        require(analysis["trend"] is String || analysis["summary"] is String) { "AI 返回的分析不完整，请重试" }
        val cards = buildAnalysisCards(detail, analysis)
        return AIAnalysisData(
            code = detail.info?.code ?: "",
            name = detail.info?.name,
            analysis = analysis.mapValues { it.value?.toString() ?: "" },
            cards = cards,
        )
    }

    private fun buildIndexAnalysisPrompt(detail: StockDetailData): List<Pair<String, String>> {
        val info = detail.info
        val infoText = buildString {
            info?.let {
                append("\n- 代码：${it.code}")
                append("\n- 名称：${it.name ?: "未知"}")
                append("\n- 市场：${it.plate ?: "未知"}")
            }
        }
        val r = detail.realtime
        val realtimeText = if (r != null) buildString {
            append("\n- 最新点位：${r.price ?: "-"}")
            append("\n- 涨跌幅：${r.changePercent ?: "-"}%")
            append("\n- 开盘点位：${r.openPrice ?: "-"}")
            append("\n- 最高点位：${r.high ?: "-"}")
            append("\n- 最低点位：${r.low ?: "-"}")
            append("\n- 成交量：${r.volume ?: "-"} 股")
            append("\n- 成交额：${r.amount ?: "-"} 元")
        } else ""

        val kline = detail.kline.orEmpty()
        val klineText = if (kline.isNotEmpty()) {
            val recent = kline.takeLast(10)
            recent.joinToString("\n") { k ->
                "  ${k.tradeDate}: 开${k.open} 收${k.close} 高${k.high} 低${k.low} 量${k.volume}"
            }
        } else ""

        val system = """你是一位专业的指数与市场策略分析师，请对以下股票指数进行全面的综合分析。

请从以下维度进行分析，并以 JSON 格式返回：
1. 趋势判断 (trend): 短期/中期趋势如何？用一句话描述
2. 技术信号 (signals): K线形态与量能配合给出哪些信号？列出所有发现的信号
3. 支撑压力位 (support_price, resistance_price): 关键的支撑点位和压力点位在哪里？
4. 风险评估 (risk_level, risks): 当前风险等级（低/中/高）？需要注意哪些风险因素？
5. 操作建议 (suggestion): 买入/卖出/持有/观望？目标点位和止损点位？
6. 总结 (summary): 一句话总结当前该指数的点位位置与风险收益特征

注意：没有技术指标与估值数据，不要编造均线/MACD/RSI/KDJ 数值与 PE/PB。

请额外提供 evidence 数组，最多6项，每项包含 date（必须是下面提供的某个K线日期）和 reason（该日行情如何支持你的判断）。无法定位时返回空数组，不得编造日期或数值。

JSON 格式示例：
{
  "trend": "趋势描述",
  "signals": ["信号1", "信号2"],
  "evidence": [],
  "support_price": "支撑点位",
  "resistance_price": "压力点位",
  "risk_level": "低|中|高",
  "risks": ["风险因素1", "风险因素2"],
  "suggestion": "买入|卖出|持有|观望",
  "target_price": "目标点位",
  "stop_loss": "止损点位",
  "summary": "一句话总结"
}"""

        val user = """请分析以下指数：

## 指数基础信息
$infoText

## 实时行情
$realtimeText

## 近期K线数据（最近10个交易日）
$klineText

请根据以上数据进行全面分析，重点结合点位位置、K线形态与成交量变化给出专业判断。"""

        return listOf("system" to system, "user" to user)
    }

    private fun buildAnalysisPrompt(detail: StockDetailData): List<Pair<String, String>> {
        val industryText = runCatching { StockDb.industryPeers(detail.info?.code.orEmpty())?.evidence(detail.info?.code.orEmpty()) }.getOrNull().orEmpty()
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

        val ff = detail.fundFlow.orEmpty()
        val fundFlowText = if (ff.isNotEmpty()) buildString {
            append("\n## 主力资金流向（日级，近 ${ff.size} 个交易日）")
            ff.forEach { f ->
                val sign = if (f.mainRatio > 0) "+" else ""
                append("\n- ${f.tradeDate}: 主力净流入 ${fmtMoney(f.mainNet)}, 净占比 ${sign}${fmtRatio1(f.mainRatio)}%")
            }
            val sum = ff.sumOf { it.mainNet }
            append("\n- 区间累计: ${fmtMoney(sum)}（${if (sum >= 0) "净流入" else "净流出"}）")
        } else ""

        val system = """你是一位专业的股票分析师，请对以下股票进行全面的综合分析。

${DetailProtocolV2.PROMPT}"""

        val datePool = if (kline.isNotEmpty()) {
            "\n\n【可选K线日期池】（日期字段必须选自下列集合，禁止编造）：\n${kline.map { it.tradeDate }.joinToString(", ")}"
        } else ""

        val user = """请分析以下股票：

## 股票基础信息
$infoText

## 实时行情
$realtimeText

## 近期K线数据（最近10个交易日）
$klineText
$datePool

## 最新技术指标（程序计算，非AI生成）
$indicatorText

$fundFlowText

$industryText

请根据以上数据进行全面分析，需结合技术指标（均线排列、MACD金叉死叉、RSI超买超卖、KDJ钝化等）与主力资金流向（净流入/净流出趋势、占比变化）给出专业判断。"""

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
                "support_value" to numericLevel(a["support_price"]),
                "resistance_value" to numericLevel(a["resistance_price"]),
                "data_date" to (detail.indicator?.tradeDate ?: ""),
                "indicator_date" to (detail.indicator?.tradeDate ?: ""),
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
        cards.add(mapOf("type" to "summary_card", "title" to "数据来源",
            "summary" to "技术指标来自本地 SQLite（真实计算值），AI 内容由当前启用的服务生成。",
            "color" to "#90A4AE"))
        val evidence = validatedAnalysisEvidence(a["evidence"], detail.kline.orEmpty().takeLast(10))
        if (evidence.isNotEmpty()) cards.add(mapOf("type" to "evidence_card", "items" to evidence))
        return cards
    }

    internal fun numericLevel(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() && it > 0.0 }
        else -> Regex("""\d+(?:\.\d+)?""").find(value?.toString().orEmpty())
            ?.value
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }

    internal suspend fun chat(
        message: String,
        mentioned: List<StockListItem>,
        history: List<Pair<String, String>>,
        onText: suspend (String) -> Unit,
        onStage: suspend (String) -> Unit,
        buildContext: () -> ChatPromptContext,
    ): ChatResult {
        onStage("正在读取本地行情…")
        val context = buildContext()
        val prompt = buildChatPrompt(message, context)
        val messages = listOf(prompt.first()) + boundedHistory(history) + prompt.last()
        val raw = ChatTransport.generate(AiRuntimeConfig.current(), messages, onText, onStage)
        onStage("正在校验卡片与图表…")
        return decodeChatReply(raw)
    }

    private fun buildChatPrompt(message: String, context: ChatPromptContext): List<Pair<String, String>> {
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

$CHAT_PROTOCOL_PROMPT"""

        val user = if (!context.hasData) {
            "用户问题：$message\n\n请根据以上信息给出专业、准确的回答。"
        } else {
            """用户问题：$message

${context.render()}

请根据以上信息给出专业、准确的回答。"""
        }
        return listOf("system" to system, "user" to user)
    }

private fun appendIndexLines(indexLines: MutableList<String>, s: StockListItem) {
val detail = try { StockDb.indexDetail(s.code) } catch (e: Throwable) { null } ?: return
val r: RealtimeQuoteData = detail.realtime ?: return
indexLines.add(
"- ${r.name ?: s.code}(${s.code})[指数]: 最新点位 ${r.price}, 涨跌幅 ${r.changePercent}%, " +
"开盘 ${r.openPrice}, 最高 ${r.high}, 最低 ${r.low}, " +
"成交量 ${r.volume} 股, 成交额 ${r.amount} 元（指数无 PE/PB/技术指标，不要编造）"
)
val kline = detail.kline.orEmpty()
if (kline.isNotEmpty()) {
val recent = kline.takeLast(5)
indexLines.add("  近${recent.size}日K线: " + recent.joinToString(", ") {
"${it.tradeDate} 开${it.open} 收${it.close} 高${it.high} 低${it.low}"
})
}
}

fun buildChatContext(message: String, mentioned: List<StockListItem>): ChatPromptContext {

val stockLines = mutableListOf<String>()
val marketLines = mutableListOf<String>()
val indexLines = mutableListOf<String>()
for (s in mentioned) {
if (s.isIndex) { appendIndexLines(indexLines, s); continue }
  val detail = try { StockDb.stockDetail(s.code) } catch (e: Throwable) { null } ?: continue
  runCatching { StockDb.industryPeers(s.code)?.evidence(s.code) }.getOrNull()?.let { stockLines.add(it) }
val r: RealtimeQuoteData = detail.realtime ?: continue
stockLines.add(
                "- ${r.name ?: s.code}(${s.code}): 最新价 ${r.price}, 涨跌幅 ${r.changePercent}%, " +
                    "开盘 ${r.openPrice}, 最高 ${r.high}, 最低 ${r.low}, " +
                    "成交量 ${r.volume} 手, 成交额 ${r.amount} 元, PE(TTM) ${r.peTtm}, PB ${r.pb}"
            )
val kline = detail.kline.orEmpty()
if (kline.isNotEmpty()) {
val recent = kline.takeLast(5)
stockLines.add("  近${recent.size}日K线: " + recent.joinToString(", ") {
"${it.tradeDate} 开${it.open} 收${it.close} 高${it.high} 低${it.low}"
                })
            }
detail.indicator?.let { ind ->
stockLines.add(
                    "  技术指标(${ind.tradeDate}): MA5=${fmt(ind.ma5)}, MA10=${fmt(ind.ma10)}, MA20=${fmt(ind.ma20)}, " +
                        "MACD(DIF=${fmt(ind.dif)}/DEA=${fmt(ind.dea)}/柱=${fmt(ind.macd)}), " +
                        "RSI6=${fmt(ind.rsi6)}, KDJ(K=${fmt(ind.kdjK)}/D=${fmt(ind.kdjD)}/J=${fmt(ind.kdjJ)})"
                )
            }
detail.fundFlow?.takeLast(5)?.let { ff ->
if (ff.isNotEmpty()) {
val sum = ff.sumOf { it.mainNet }
stockLines.add(
                        "  主力资金(近${ff.size}日): " + ff.joinToString(", ") {
                            "${it.tradeDate} 净流入${fmtMoney(it.mainNet)}(${it.mainRatio}%)"
                        } + ", 累计${fmtMoney(sum)}(${if (sum >= 0) "净流入" else "净流出"})"
                    )
                }
            }
        }
if (isMarketQuestion(message)) {
val ov = try { StockDb.marketOverview() } catch (e: Throwable) { null }
if (ov != null) {
marketLines.add("- 全市场概览: 共 ${ov.total} 只, 上涨 ${ov.up} 家, 下跌 ${ov.down} 家, 平盘 ${ov.flat} 家")
marketLines.add("- 涨幅榜: " + ov.topGainers.joinToString(", ") { "${it.name ?: it.code}(+${it.changePercent}%)" })
marketLines.add("- 跌幅榜: " + ov.topLosers.joinToString(", ") { "${it.name ?: it.code}(${it.changePercent}%)" })
            }
        }
return ChatPromptContext(message, mentioned, stockLines, marketLines, indexLines)
    }

private fun isMarketQuestion(message: String): Boolean {
val keywords = listOf("大盘", "行情", "市场", "指数", "涨幅榜", "跌幅榜", "涨跌", "板块", "整体")
return keywords.any { message.contains(it) }
    }

private fun fmt(v: Double?): String = if (v == null) "-" else fmt3(v)

/** 净占比保留 1 位小数（-15.7）。纯 Kotlin 实现，跨平台可用。 */
private fun fmtRatio1(v: Double): String = com.kuikly.stock.data.fmt1(v)

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
