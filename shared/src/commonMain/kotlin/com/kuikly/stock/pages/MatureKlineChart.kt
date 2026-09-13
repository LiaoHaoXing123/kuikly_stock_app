package com.kuikly.stock.pages

import com.kuikly.stock.ui.theme.ThemeManager
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

private class MatureChartView : ViewContainer<ContainerAttr, Event>() {
    override fun createAttr() = ContainerAttr()
    override fun createEvent() = Event()
    override fun viewName() = "StockKlineWebView"
}

internal fun ViewContainer<*, *>.matureKlineChart(ctx: StockDetailPage) {
    addChild(MatureChartView()) {
        attr {
            height(600f)
            val bars = JSONArray()
            ctx.getAggregatedKline().forEach { b ->
                val d = normalizedTradeDate(b.tradeDate)
                if (d.length == 8) bars.put(JSONObject().put("date", "${d.take(4)}-${d.substring(4, 6)}-${d.takeLast(2)}")
                    .put("open", b.open).put("close", b.close).put("high", b.high).put("low", b.low).put("volume", b.volume))
            }
            setProp("chartData", JSONObject().put("code", ctx.stockCode).put("period", ctx.klinePeriod)
                .put("dark", ThemeManager.isDark).put("bars", bars)
                .put("focus", ctx.selectedKlineIndex).put("highlight", ctx.highlightedPrice).toString())
        }
        event {
            register("chartSelection") { value ->
                val p = value as? JSONObject ?: return@register
                val bars = ctx.getAggregatedKline()
                val from = p.optInt("from", -1)
                val to = p.optInt("to", -1)
                if (from !in bars.indices || to !in bars.indices || to < from) return@register
                val visibleFrom = p.optInt("visibleFrom", 0).coerceIn(0, bars.lastIndex)
                val visibleTo = p.optInt("visibleTo", bars.size).coerceIn(visibleFrom + 1, bars.size)
                ctx.klineStartIndex = visibleFrom
                ctx.klineVisibleCount = visibleTo - visibleFrom
                ctx.selectedKlineIndex = to
                ctx.rangeStats = if (p.optString("kind") == "range") summarizeRange(bars, from, to) else null
                ctx.askAboutChart(p.optString("indicator", "VOL"))
            }
        }
    }
}
