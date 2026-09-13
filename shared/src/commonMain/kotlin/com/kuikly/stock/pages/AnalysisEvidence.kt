package com.kuikly.stock.pages

internal fun validatedAnalysisEvidence(raw: Any?, suppliedCandles: List<KLineDataItem>): List<Map<String, Any?>> {
    val dates = suppliedCandles.associate { normalizedTradeDate(it.tradeDate) to it.tradeDate }
    return (raw as? List<*>).orEmpty().mapNotNull { value ->
        val item = value as? Map<*, *> ?: return@mapNotNull null
        val date = (item["date"] as? String)?.let { dates[normalizedTradeDate(it)] } ?: return@mapNotNull null
        val reason = (item["reason"] as? String)?.trim()?.take(600)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        mapOf<String, Any?>("date" to date, "reason" to reason)
    }.distinct().take(6)
}
