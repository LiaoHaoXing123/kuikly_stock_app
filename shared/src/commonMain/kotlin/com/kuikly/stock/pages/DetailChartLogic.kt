package com.kuikly.stock.pages

import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct

internal fun normalizedTradeDate(date: String): String = date.filter { it in '0'..'9' }.take(8)

internal fun retainLongerHistory(current: List<KLineDataItem>, incoming: List<KLineDataItem>): List<KLineDataItem> =
    if (current.size > incoming.size && normalizedTradeDate(current.lastOrNull()?.tradeDate.orEmpty()) >=
        normalizedTradeDate(incoming.lastOrNull()?.tradeDate.orEmpty())) current else incoming

private fun weekKey(date: String): String {
    val digits = normalizedTradeDate(date)
    if (digits.length != 8) return date
    val year = digits.take(4).toIntOrNull() ?: return date
    val month = digits.substring(4, 6).toIntOrNull() ?: return date
    val day = digits.takeLast(2).toIntOrNull() ?: return date
    if (year !in 1..9999 || month !in 1..12) return date
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val months = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    if (day !in 1..months[month - 1]) return date
    val y = year - 1
    val ordinal = 365 * y + y / 4 - y / 100 + y / 400 + months.take(month - 1).sum() + day
    return ((ordinal - 1) / 7).toString()
}

internal fun aggregateToWeekly(daily: List<KLineDataItem>): List<KLineDataItem> = aggregateCandles(daily, ::weekKey)
internal fun aggregateToMonthly(daily: List<KLineDataItem>): List<KLineDataItem> =
    aggregateCandles(daily) { normalizedTradeDate(it).take(6) }

private fun aggregateCandles(daily: List<KLineDataItem>, key: (String) -> String): List<KLineDataItem> =
    daily.sortedBy { normalizedTradeDate(it.tradeDate) }.groupBy { key(it.tradeDate) }.values.map { bars ->
        bars.first().copy(
            tradeDate = bars.last().tradeDate, close = bars.last().close,
            high = bars.maxOf { it.high }, low = bars.minOf { it.low }, volume = bars.sumOf { it.volume },
            amount = if (bars.any { it.amount != null }) bars.sumOf { it.amount ?: 0.0 } else null,
        )
    }

internal fun chartHitIndex(x: Float, width: Float, count: Int): Int {
    if (count <= 0 || width <= 0 || !width.isFinite() || !x.isFinite()) return -1
    return ((x / width) * count).toInt().coerceIn(0, count - 1)
}

internal data class ChartEvidence(val index: Int, val title: String, val detail: String)

internal fun candleEvidence(data: List<KLineDataItem>, index: Int): String {
    val bar = data.getOrNull(index) ?: return "点击K线查看该时段的价格与成交量依据"
    val previous = data.getOrNull(index - 1)?.close
    val change = previous?.takeIf { it > 0 }?.let { "较前收 ${fmtSignedPct((bar.close / it - 1) * 100)}" }
        ?: "首条数据，无前收对比"
    val volumes = data.take(index).takeLast(5).map { it.volume }
    val mean = volumes.takeIf { it.size == 5 && it.all { v -> v.isFinite() && v >= 0 } }?.average()
    val ratio = mean?.takeIf { it > 0 }?.let { "；成交量为前5期均量 ${fmt2(bar.volume / it)} 倍" }.orEmpty()
    return "${bar.tradeDate} · 开 ${fmt2(bar.open)} / 收 ${fmt2(bar.close)}\n高 ${fmt2(bar.high)} / 低 ${fmt2(bar.low)}；$change$ratio"
}

internal fun chartEvidence(data: List<KLineDataItem>): List<ChartEvidence> = data.indices.mapNotNull { i ->
    val bar = data[i]
    val previous = data.take(i).takeLast(5)
    val meanVolume = previous.takeIf { it.size == 5 }?.map { it.volume }?.average() ?: 0.0
    val high = previous.maxOfOrNull { it.high } ?: Double.POSITIVE_INFINITY
    val volumeUp = meanVolume > 0 && bar.volume.isFinite() && bar.volume >= meanVolume * 1.5
    val breakout = previous.size == 5 && bar.close > high
    val title = when {
        volumeUp && breakout -> "放量突破前5期高点"
        volumeUp -> "成交量放大"
        breakout -> "收盘突破前5期高点"
        else -> return@mapNotNull null
    }
    ChartEvidence(i, title, candleEvidence(data, i))
}.takeLast(6).reversed()

internal fun detailFollowupPrompt(
    kind: String, code: String, name: String, period: String,
    data: List<KLineDataItem>, selected: Int, analysis: AIAnalysisData?,
    range: RangeStats? = null, viewport: List<KLineDataItem> = data,
    indicator: String = "none",
): String = buildString {
    append("请解释${if (kind == "index") "指数" else "股票"} $name($code) 的选中行情，并说明依据和不确定性。\n")
    append("周期：${when (period) { "W" -> "周K（自然周）"; "M" -> "月K"; else -> "日K" }}\n")
    append("数据类型：本地历史快照，非实时；复权口径：未提供，不得假定。\n")
    append("视口：${viewport.firstOrNull()?.tradeDate ?: "无"} 至 ${viewport.lastOrNull()?.tradeDate ?: "无"}，${viewport.size}根；副图：$indicator\n")
    if (range != null) {
        append("解读目标：选中区间 ${range.startDate} 至 ${range.endDate}，共${range.bars}根。\n")
        append("本地计算：区间涨跌幅${fmt2(range.changePct)}%（末收/首开-1）；振幅${fmt2(range.amplitude)}%（最高/最低-1）；最高${range.highPrice}；最低${range.lowPrice}；累计成交量${range.totalVolume}（沿用数据源单位）。\n")
        append("以下蜡烛是有界明细，不代表完整区间；请以区间统计为准，不得用明细重新推断区间长度。\n")
    }
    val candles = if (selected in data.indices) data.subList((selected - 5).coerceAtLeast(0), selected + 1) else data.takeLast(10)
    append("选中：${data.getOrNull(selected)?.tradeDate ?: "当前可见区间"}\n")
    candles.forEach { append("${it.tradeDate} 开${it.open} 收${it.close} 高${it.high} 低${it.low} 量${it.volume}\n") }
    append("分析来源：${analysis?.source ?: "尚未生成分析"}；分析行情日期：${analysis?.dataDate.orEmpty()}\n")
    if (analysis != null) append("已有分析（仅供复核）：${analysis.analysis.toString().take(3000)}\n")
    append("请区分上述快照与最新行情；不要把缺失的数据或历史信号当作事实。")
}
