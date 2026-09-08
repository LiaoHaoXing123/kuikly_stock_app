package com.kuikly.stock.ai.chat

/** Only complete user/assistant turns enter the next request. Latest turns win. */
internal fun boundedHistory(history: List<Pair<String, String>>, maxChars: Int = 12000, maxTurns: Int = 6): List<Pair<String, String>> {
    val turns = mutableListOf<List<Pair<String, String>>>()
    var user: Pair<String, String>? = null
    for (message in history) {
        when (message.first) {
            "user" -> user = message.takeIf { it.second.isNotBlank() }
            "assistant" -> {
                if (user != null && message.second.isNotBlank()) turns.add(listOf(user, message))
                user = null
            }
        }
    }
    val kept = mutableListOf<List<Pair<String, String>>>()
    var chars = 0
    for (turn in turns.takeLast(maxTurns.coerceAtLeast(0)).asReversed()) {
        val size = turn.sumOf { it.second.length }
        if (chars + size > maxChars) break
        kept.add(turn)
        chars += size
    }
    return kept.asReversed().flatten()
}

internal fun <T> resolveFocus(message: String, history: List<Pair<String, String>>, detect: (String) -> List<T>): List<T> {
    val explicit = detect(message)
    if (explicit.isNotEmpty()) return explicit
    val followup = listOf("它", "该股", "这只", "这支", "继续", "走势", "支撑", "压力", "风险", "技术指标", "成交量", "分时", "画图", "走势图")
    if (followup.none { message.contains(it) }) return emptyList()
    for ((role, text) in boundedHistory(history).asReversed()) {
        if (role != "user") continue
        val found = detect(text)
        if (found.isNotEmpty()) return found
    }
    return emptyList()
}
