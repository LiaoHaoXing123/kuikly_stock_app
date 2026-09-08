package com.kuikly.stock.pages

import com.kuikly.stock.ai.chat.chartPoints
import com.kuikly.stock.ai.chat.nativeToJson
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 聊天会话导出：Markdown（人阅读/分享）+ JSON（完整备份）。
 * 纯函数，无平台依赖，便于单测。
 */

/** 导出文件名前缀：去掉路径非法字符与空白，限长 24，兜底 "chat"。 */
internal fun exportFileBase(title: String): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|\\s\\x00-\\x1F]+"), "").trim().take(24)
    return if (clean.isEmpty()) "chat" else clean
}

internal fun exportSessionMarkdown(session: ChatSession, exportedAt: String): String {
    val sb = StringBuilder()
    sb.append("# ").appendLine(session.title.ifBlank { "AI 问答" })
    sb.append("> 导出时间：").append(exportedAt.ifBlank { "未知" })
        .append(" · 共 ").append(session.messages.size).append(" 条消息\n\n")
    session.messages.forEach { msg ->
        if (msg.isUser) {
            sb.append("## 我\n\n").append(msg.content.trim()).append("\n\n")
        } else {
            sb.append("## AI 助手\n\n")
            if (msg.failed) sb.append("> 注意：该条回复发送失败，内容可能不完整。\n\n")
            sb.append(msg.content.trim()).append("\n\n")
            appendCardsMarkdown(sb, msg.cards.orEmpty())
            val suggestions = msg.suggestions.orEmpty().filter { it.isNotBlank() }
            if (suggestions.isNotEmpty()) {
                sb.append("> 追问：").append(suggestions.joinToString("、") { it.trim() }).append("\n\n")
            }
        }
    }
    sb.append("---\n由 AI 股票助手导出，仅供学习参考，不构成投资建议。\n")
    return sb.toString()
}

internal fun exportSessionJson(session: ChatSession, exportedAt: String): String {
    val doc = mapOf(
        "app" to "kuikly_stock_app",
        "format" to "chat-session/1",
        "sessionId" to session.id,
        "title" to session.title,
        "exportedAt" to exportedAt,
        "messageCount" to session.messages.size,
        "messages" to session.messages.map { msg ->
            mapOf(
                "role" to msg.role,
                "content" to msg.content,
                "isUser" to msg.isUser,
                "failed" to msg.failed,
                "cards" to (msg.cards ?: emptyList<Map<String, Any?>>()),
                "suggestions" to (msg.suggestions ?: emptyList<String>())
            )
        }
    )
    val pretty = Json { prettyPrint = true }
    return pretty.encodeToString(JsonElement.serializer(), nativeToJson(doc))
}

private fun appendCardsMarkdown(sb: StringBuilder, cards: List<Map<String, Any?>>) {
    if (cards.isEmpty()) return
    sb.append("**相关卡片**\n\n")
    cards.forEach { card -> sb.append("- ").appendLine(summarizeCard(card)) }
    sb.append("\n")
    cards.forEach { card ->
        when ((card["type"] as? String)) {
            "chart_card" -> appendChartTable(sb, card)
            "compare_card" -> appendCompareTable(sb, card)
        }
    }
}

internal fun summarizeCard(card: Map<String, Any?>): String {
    val type = card["type"] as? String ?: "unknown"
    fun num(vararg keys: String): Double? {
        keys.forEach { k -> (card[k] as? Number)?.toDouble()?.let { return it } }
        return null
    }
    fun str(key: String): String = (card[key] as? String)?.trim().orEmpty()
    return when (type) {
        "stock_card", "index_card" -> {
            val label = if (type == "index_card") "指数" else "个股"
            val price = num("price")?.let { " 现价 " + fmt2(it) + "元" }.orEmpty()
            val pct = num("changePercent", "change_percent")?.let { " " + fmtSignedPct(it) }.orEmpty()
            "[$label] ${str("name")}(${str("code")})$price$pct".trim()
        }
        "conclusion_card" -> {
            val action = str("action")
            "[结论] ${str("bias")}：${str("one_liner")}" + (if (action.isNotEmpty()) "（建议：$action）" else "")
        }
        "compare_card" -> "[对比] ${str("title").ifEmpty { "多标的对比" }}（详见下表）"
        "chart_card" -> {
            val points = chartPoints(card)
            if (points.size < 2) {
                "[图表] ${str("title").ifEmpty { "走势" }}（数据不足）"
            } else {
                "[图表] ${str("title")}（${points.first().label}—${points.last().label}" +
                    " · ${points.size} 个点，最新 ${fmt2(points.last().value)}${str("unit")}，详见下表）"
            }
        }
        "trend_card" -> "[趋势判断] ${str("content")}"
        "signal_card" -> "[技术信号] ${str("content")}"
        "risk_card" -> "[风险评估] ${str("content")}"
        "suggestion_card" -> "[操作建议] ${str("content")}"
        "summary_card" -> "[AI 总结] ${str("content")}"
        else -> {
            val hint = str("content").ifEmpty { str("text") }.take(120)
            "[卡片:$type]" + (if (hint.isNotEmpty()) " $hint" else "")
        }
    }
}

private fun mdCell(s: String): String = s.replace("|", "｜").replace("\n", " ").trim()

private fun appendChartTable(sb: StringBuilder, card: Map<String, Any?>) {
    val points = chartPoints(card)
    if (points.isEmpty()) return
    val title = (card["title"] as? String)?.trim().orEmpty().ifEmpty { "图表数据" }
    sb.append("**").append(title).append("**\n\n")
    sb.append("| 日期 | 数值 |\n| --- | --- |\n")
    points.forEach { p -> sb.append("| ").append(mdCell(p.label)).append(" | ").append(fmt2(p.value)).append(" |\n") }
    sb.append("\n")
}

private fun appendCompareTable(sb: StringBuilder, card: Map<String, Any?>) {
    val headers = (card["headers"] as? String).orEmpty().split("|").map { it.trim() }.filter { it.isNotEmpty() }
    val rows = when (val r = card["rows"]) {
        is String -> r.split("\n")
        is List<*> -> r.map { it?.toString().orEmpty() }
        else -> emptyList()
    }.map { line -> line.split("|").map { it.trim() } }.filter { row -> row.any { it.isNotEmpty() } }
    val cols = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    if (cols <= 0) return
    val title = (card["title"] as? String)?.trim().orEmpty().ifEmpty { "对比" }
    sb.append("**").append(title).append("**\n\n")
    val head = if (headers.isNotEmpty()) headers else List(cols) { "列${it + 1}" }
    sb.append("| ").append(head.map(::mdCell).joinToString(" | ")).append(" |\n")
    sb.append("| ").append(List(cols) { "---" }.joinToString(" | ")).append(" |\n")
    rows.forEach { row ->
        val cells = (0 until cols).map { i -> mdCell(row.getOrNull(i).orEmpty()) }
        sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
    }
    sb.append("\n")
}
