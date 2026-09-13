// 结合名称和语境区分同代码的股票与指数。

package com.kuikly.stock.data

import com.kuikly.stock.pages.StockListItem

data class IndexCandidate(val code: String, val name: String?)

internal val INDEX_CONTEXT_WORDS = listOf(
    "指数", "大盘", "点位", "上证", "深证", "沪深", "中证",
    "创业板", "科创", "北证", "中小板", "红利", "成指", "沪指", "深指",
)

private val INDEX_ALIASES = mapOf(
    "大盘" to "000001",
    "上证" to "000001",
    "沪指" to "000001",
    "深证" to "399001",
    "深指" to "399001",
    "创业板" to "399006",
    "科创板" to "000688",
)

internal fun matchIndexCandidates(message: String, candidates: List<IndexCandidate>): List<StockListItem> {
    if (message.isBlank() || candidates.isEmpty()) return emptyList()
    val found = mutableListOf<StockListItem>()
    fun add(code: String, name: String?) {
        if (found.size >= 3 || found.any { it.code == code }) return
        found.add(StockListItem(code, name, isIndex = true))
    }
    val hasContext = INDEX_CONTEXT_WORDS.any { it in message }

    for (c in candidates) {
        val name = c.name?.trim().orEmpty()
        if (name.length < 2) continue
        if (name in message) {
            add(c.code, c.name)
        } else if (hasContext) {
            val short = name.removeSuffix("指数").removeSuffix("指")
            if (short.length >= 2 && short != name && short in message) add(c.code, c.name)
        }
        if (found.size >= 3) return found
    }

    for ((alias, code) in INDEX_ALIASES) {
        if (alias in message) {
            candidates.firstOrNull { it.code == code }?.let { add(it.code, it.name) }
        }
    }

    if (hasContext) {
        for (code in findSixDigitCodes(message)) {
            candidates.firstOrNull { it.code == code }?.let { add(it.code, it.name) }
        }
    }
    return found.take(3)
}

private fun findSixDigitCodes(message: String): Set<String> {
    val out = mutableSetOf<String>()
    var i = 0
    while (i < message.length) {
        if (!message[i].isDigit()) {
            i++
            continue
        }
        var j = i
        while (j < message.length && message[j].isDigit()) j++
        if (j - i == 6) out.add(message.substring(i, j))
        i = j
    }
    return out
}
