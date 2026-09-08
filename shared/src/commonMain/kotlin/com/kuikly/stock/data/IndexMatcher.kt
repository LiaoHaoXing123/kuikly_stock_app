// 指数识别：纯函数实现，便于单测。各平台 StockDb 只负责从 index_info 取候选，匹配逻辑收敛于此。
//
// 消歧规则（指数代码与个股代码重叠，如 000001 既是上证指数又是平安银行）：
// 1. 指数全名/别名命中 -> 直接算指数（如"上证指数"、"大盘"、"创业板"）；
// 2. 去后缀简称命中 -> 仅在有指数语境时算数（如"上证"；避免"银行"误命中"银行指数"）；
// 3. 6 位代码命中 -> 必须有指数语境，否则默认走个股（"000001"单独出现仍是平安银行）。

package com.kuikly.stock.data

import com.kuikly.stock.pages.StockListItem

data class IndexCandidate(val code: String, val name: String?)

/** 出现这些词才认为 6 位数字代码 / 简称指指数。 */
internal val INDEX_CONTEXT_WORDS = listOf(
    "指数", "大盘", "点位", "上证", "深证", "沪深", "中证",
    "创业板", "科创", "北证", "中小板", "红利", "成指", "沪指", "深指",
)

/** 常见简称 -> 指数代码（仅当代码存在于候选表中才生效）。 */
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
    // 1) 全名命中；去后缀简称仅在有语境时命中
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
    // 2) 别名命中
    for ((alias, code) in INDEX_ALIASES) {
        if (alias in message) {
            candidates.firstOrNull { it.code == code }?.let { add(it.code, it.name) }
        }
    }
    // 3) 代码命中：必须有指数语境
    if (hasContext) {
        for (code in findSixDigitCodes(message)) {
            candidates.firstOrNull { it.code == code }?.let { add(it.code, it.name) }
        }
    }
    return found.take(3)
}

/** 提取恰好 6 位的独立数字串（等价于正则 (?<!\d)\d{6}(?!\d)，手写避免转义问题）。 */
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
