package com.kuikly.stock.pages

internal fun analysisDateLabel(analysisDate: String, latestDate: String): String {
    val analysis = normalizedTradeDate(analysisDate)
    val latest = normalizedTradeDate(latestDate)
    fun display(date: String) = "${date.take(4)}-${date.substring(4, 6)}-${date.takeLast(2)}"
    if (analysis.length != 8) return "分析行情日期未提供，请核对数据"
    val prefix = "分析基于 ${display(analysis)}"
    return if (latest.length == 8 && analysis != latest) {
        "$prefix · 图表截至 ${display(latest)}，日期不同"
    } else prefix
}
