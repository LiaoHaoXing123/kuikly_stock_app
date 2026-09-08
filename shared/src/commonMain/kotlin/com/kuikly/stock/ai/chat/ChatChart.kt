package com.kuikly.stock.ai.chat

import com.kuikly.stock.pages.KLineDataItem
import kotlinx.serialization.json.*

internal fun buildLocalChart(code: String, name: String, bars: List<KLineDataItem>, volume: Boolean): Map<String, Any?>? {
    val points = bars.takeLast(120).mapNotNull { bar ->
        val value = if (volume) bar.volume else bar.close
        if (!value.isFinite() || value < 0 || bar.tradeDate.isBlank()) null
        else mapOf("label" to bar.tradeDate, "value" to value)
    }
    if (points.size < 2) return null
    return mapOf("type" to "chart_card", "code" to code, "title" to "$name · ${if (volume) "成交量" else "收盘走势"}",
        "chart_type" to if (volume) "bar" else "line", "data" to points,
        "source" to "本地日 K 线", "unit" to if (volume) "手" else "元")
}

internal data class ChartPoint(val label: String, val value: Double)
internal fun chartPoints(card: Map<String, Any?>): List<ChartPoint> = (card["data"] as? List<*>).orEmpty().take(120).mapNotNull {
    val point = it as? Map<*, *> ?: return@mapNotNull null
    val value = (point["value"] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
    val label = point["label"] as? String ?: return@mapNotNull null
    ChartPoint(label.take(40), value)
}

internal fun nativeToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to nativeToJson(it.value) })
    is List<*> -> JsonArray(value.map(::nativeToJson))
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
