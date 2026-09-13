package com.kuikly.stock.ai.chat

import com.kuikly.stock.ai.protocol.ChatProtocolV1
import com.kuikly.stock.ai.protocol.ValidateReport
import com.kuikly.stock.ai.protocol.validateCards
import com.kuikly.stock.data.ChatResult
import kotlinx.serialization.json.*

internal class ChatProtocolException(message: String) : IllegalArgumentException(message)
internal val strictJson = Json { isLenient = false }

internal const val CHAT_PROTOCOL_PROMPT = """
必须仅输出一个合法 JSON 对象，不要代码围栏或 JSON 外的解释。协议版本为 1。
顶层格式：{"version":1,"text":"Markdown说明","cards":[],"suggestions":["后续问题"]}。
先输出 version、text，再输出 cards、suggestions；text 非空且不超过 12000 字，cards 最多 8 个，suggestions 最多 3 个字符串。
卡片只支持以下类型，禁止输出其他类型和未定义字段：
1. stock_card: type,code(6位字符串),name,price(有限正数),change_percent(字符串)。
2. conclusion_card: type,code,name,bias(偏强/偏弱/中性),one_liner 必填；可选字符串 bias_note,change_percent,support,resistance,data_date,indicator_date,action,footnote；可选正数或 null 的 support_value,resistance_value；signals 为字符串数组，最多 5 项。
3. signal_card 或 risk_card: type,content(字符串)。
4. chart_card: type,title,code(6位字符串),chart_type(line/bar),data([{label:日期字符串,value:有限数字}])。data 最多 120 项，至少 2 项；仅引用工具提供的数据，没有数据就省略卡片。走势问题请提供 chart_card。
5. index_card: type,code(6位字符串),name,price(有限正数),change_percent(字符串)，与 stock_card 同形、仅用于指数。
指数问题必须用 index_card（不要用 stock_card）；conclusion_card 仅用于个股，不要对指数输出结论卡。
不要把数字或数组转换成字符串，不要编造价格或日期。价位缺失时省略数值字段。风险提示只能作为研究信息，不能承诺收益。
历史对话仅用于理解追问；本轮附带数据和工具查询才是价格依据。
"""

internal fun decodeChatReply(raw: String): ChatResult {
    if (raw.length > 100000) throw ChatProtocolException("AI 响应过长，请缩小问题范围后重试")
    val body = extractJsonBody(raw)
    val root = try { strictJson.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        ?: throw ChatProtocolException("AI 返回的 JSON 不完整或格式错误，请重试")
    val required = setOf("version", "text", "cards", "suggestions")
    if (!root.keys.containsAll(required)) throw ChatProtocolException("AI 响应字段不符合协议，请重试")
    val version = root["version"] as? JsonPrimitive
    if (version == null || version.isString || version.intOrNull != 1) throw ChatProtocolException("AI 响应协议版本不支持")
    val text = root.string("text")?.takeIf { it.isNotBlank() && it.length <= 12000 }
        ?: throw ChatProtocolException("AI 回复缺少有效正文")
    val cards = root["cards"] as? JsonArray ?: throw ChatProtocolException("cards 必须为数组")
    val suggestions = root["suggestions"] as? JsonArray ?: throw ChatProtocolException("suggestions 必须为数组")
    if (suggestions.size > 3 || suggestions.any { it !is JsonPrimitive || !it.isString || it.content.length !in 1..160 })
        throw ChatProtocolException("AI 回复超出协议限制")

    val report = ValidateReport()
    val validCards = validateCards(
        raw = cards.map { it.toNativeValue() },
        registry = ChatProtocolV1.registry,
        report = report,
        maxCards = ChatProtocolV1.MAX_CARDS,
    )

    val notice = when {
        report.droppedCards > 0 -> "${report.droppedCards} 张卡片格式无效，已隐藏；可重试生成"
        else -> null
    }
    return ChatResult(
        text = text,
        cards = validCards.ifEmpty { null },
        suggestions = suggestions.map { it.jsonPrimitive.content },
        errorNotice = notice,
    )
}

internal fun extractJsonBody(raw: String): String {
    var s = raw.trim()
    val fence = "```"
    if (s.startsWith(fence)) {
        val start = s.indexOf('\n', fence.length)
        val end = s.lastIndexOf(fence)
        if (start in 0 until end) s = s.substring(start + 1, end).trim()
    }
    val firstBrace = s.indexOf('{')
    val lastBrace = s.lastIndexOf('}')
    return if (firstBrace in 0 until lastBrace) s.substring(firstBrace, lastBrace + 1) else s
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
private fun validCard(card: JsonObject): Boolean {
    fun text(key: String) = card.string(key)?.let { it.length in 1..2000 } == true
    fun code() = card.string("code")?.matches(Regex("[0-9]{6}")) == true
    fun keys(vararg names: String) = card.keys.all { it == "type" || it in names }
    return when (card.string("type")) {
        "stock_card", "index_card" -> keys("code", "name", "price", "change_percent") && code() && text("name") && (card.number("price") ?: -1.0) > 0 && text("change_percent")
        "conclusion_card" -> {
            val strings = setOf("code", "name", "bias", "one_liner", "bias_note", "change_percent", "support", "resistance", "data_date", "indicator_date", "action", "footnote")
            code() && text("name") && text("one_liner") && card.string("bias") in setOf("偏强", "偏弱", "中性") &&
                card.all { (key, value) -> when (key) {
                    "type" -> true
                    in strings -> value is JsonPrimitive && value.isString && value.content.length <= 2000
                    "support_value", "resistance_value" -> value == JsonNull || (card.number(key) ?: -1.0) > 0
                    "signals" -> value is JsonArray && value.size <= 5 && value.all { it is JsonPrimitive && it.isString && it.content.length <= 500 }
                    else -> false
                } }
        }
        "signal_card", "risk_card" -> keys("content") && text("content")
        "chart_card" -> keys("title", "code", "chart_type", "data") && text("title") &&
            code() && card.string("chart_type") in setOf("line", "bar") &&
            (card["data"] as? JsonArray)?.let { data -> data.size in 2..120 && data.all {
                val point = it as? JsonObject
                point != null && point.keys == setOf("label", "value") &&
                    (point.string("label")?.length ?: 0) in 1..40 && point.number("value") != null
            } } == true
        else -> false
    }
}

internal fun JsonElement.toNativeValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toNativeValue() }
    is JsonArray -> map { it.toNativeValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: doubleOrNull
}

internal fun partialReplyText(raw: String): String {
    var depth = 0
    var i = 0
    while (i < raw.length) {
        when (raw[i]) {
            '{', '[' -> depth++
            '}', ']' -> depth--
            '"' -> {
                val start = i + 1
                i++
                while (i < raw.length && raw[i] != '"') { if (raw[i] == '\\') i++; i++ }
                if (i >= raw.length) return ""
                val key = raw.substring(start, i)
                var next = i + 1
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (depth == 1 && key == "text" && next < raw.length && raw[next] == ':') {
                    next++
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next >= raw.length || raw[next] != '"') return ""
                    return decodePartialString(raw, next + 1)
                }
            }
        }
        i++
    }
    return ""
}

private fun decodePartialString(raw: String, start: Int): String {
    val out = StringBuilder()
    var i = start
    while (i < raw.length) {
        val c = raw[i++]
        if (c == '"') break
        if (c != '\\') { out.append(c); continue }
        if (i >= raw.length) break
        when (val escape = raw[i++]) {
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            '"', '\\', '/' -> out.append(escape)
            'u' -> {
                if (i + 4 > raw.length) break
                val code = raw.substring(i, i + 4).toIntOrNull(16) ?: break
                out.append(code.toChar()); i += 4
            }
            else -> break
        }
    }
    if (out.isNotEmpty() && out.last().isHighSurrogate()) out.deleteAt(out.lastIndex)
    return out.toString()
}
