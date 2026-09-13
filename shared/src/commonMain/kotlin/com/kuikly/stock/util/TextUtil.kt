package com.kuikly.stock.util

internal fun normalizeBreaks(s: String): String {
    if (s.isEmpty()) return s
    var t = s.replace("<br/>", "\n").replace("<br>", "\n")
    t = t.replace("\r\n", "\n")
    t = t.replace('\r', '\n')
    return t.replace("\\n", "\n")
}

internal fun splitBreaks(s: String): List<String> {
    return normalizeBreaks(s).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}

internal fun relativeTimeLabel(at: Long, now: Long): String {
    val diff = now - at
    if (diff < MINUTE_MS) return "刚刚"
    if (diff < HOUR_MS) return "${diff / MINUTE_MS} 分钟前"
    if (diff < DAY_MS) return "${diff / HOUR_MS} 小时前"
    val days = diff / DAY_MS
    if (days < 7) return "$days 天前"
    val weeks = days / 7
    return if (weeks < 8) "$weeks 周前" else "更早"
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
