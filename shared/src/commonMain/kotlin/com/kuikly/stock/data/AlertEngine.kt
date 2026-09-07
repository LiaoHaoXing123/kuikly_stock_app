package com.kuikly.stock.data

internal fun fmt2(v: Double): String = String.format("%.2f", v)

internal fun fmtSignedPct(v: Double): String {
    val prefix = if (v > 0) "+" else ""
    return prefix + String.format("%.2f", v) + "%"
}

internal fun describeAlertType(type: Int): String = when (type) {
    0 -> "价格 ≥"
    1 -> "价格 ≤"
    2 -> "涨幅 ≥"
    else -> "跌幅 ≥"
}

internal object AlertEngine {
    fun hits(): List<Pair<PriceAlertRule, String>> {
        val rules = WatchStore.alerts()
        if (rules.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<PriceAlertRule, String>>()
        for (r in rules) {
            if (!r.enabled) continue
            val rt = try { StockDb.stockDetail(r.code)?.realtime } catch (e: Throwable) { null } ?: continue
            val price = rt.price ?: continue
            val pct = rt.changePercent ?: 0.0
            val msg = when (r.type) {
                0 -> if (price >= r.threshold) "${r.name} 现价 ${fmt2(price)} 已触及提醒价 ${fmt2(r.threshold)}" else null
                1 -> if (price <= r.threshold) "${r.name} 现价 ${fmt2(price)} 已低于提醒价 ${fmt2(r.threshold)}" else null
                2 -> if (pct >= r.threshold) "${r.name} 现价 ${fmt2(price)}，涨幅 ${fmtSignedPct(pct)} ≥ ${fmt2(r.threshold)}%" else null
                else -> if (pct <= -r.threshold) "${r.name} 现价 ${fmt2(price)}，跌幅 ${fmtSignedPct(pct)} 已达 -${fmt2(r.threshold)}%" else null
            }
            if (msg != null) out.add(r to msg)
        }
        return out
    }
}
