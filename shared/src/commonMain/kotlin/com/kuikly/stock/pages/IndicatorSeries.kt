package com.kuikly.stock.pages

// -----------------------------------------------------------------------------
// 纯函数技术指标序列：MACD / KDJ。无 UI 依赖，可单测。
// 在完整历史数据上计算（EMA/滚动窗口需要预热），调用方再按可见区间切片。
// -----------------------------------------------------------------------------

internal data class MacdSeries(
    val dif: List<Double>,
    val dea: List<Double>,
    val hist: List<Double>,
)

internal data class KdjSeries(
    val k: List<Double>,
    val d: List<Double>,
    val j: List<Double>,
)

/** 指数移动平均，从 index0 播种为首值。 */
private fun ema(values: List<Double>, period: Int): List<Double> {
    val n = values.size
    if (n == 0) return emptyList()
    val alpha = 2.0 / (period + 1).coerceAtLeast(1)
    val out = DoubleArray(n)
    out[0] = values[0]
    for (i in 1 until n) {
        out[i] = values[i] * alpha + out[i - 1] * (1.0 - alpha)
    }
    return out.toList()
}

/**
 * MACD：dif = EMA(fast) - EMA(slow)；dea = EMA(dif, signal)；
 * hist = 2 * (dif - dea)（国内软件“MACD 柱”口径）。返回与输入同长、全非空。
 */
internal fun computeMACD(
    closes: List<Double>,
    fast: Int = 12,
    slow: Int = 26,
    signal: Int = 9,
): MacdSeries {
    val n = closes.size
    if (n == 0) return MacdSeries(emptyList(), emptyList(), emptyList())
    val emaFast = ema(closes, fast)
    val emaSlow = ema(closes, slow)
    val dif = List(n) { emaFast[it] - emaSlow[it] }
    val dea = ema(dif, signal)
    val hist = List(n) { 2.0 * (dif[it] - dea[it]) }
    return MacdSeries(dif, dea, hist)
}

/**
 * KDJ：RSV = (close - lowN) / (highN - lowN) * 100，窗口 n（不足窗口用已有区间）；
 * K = (prevK*(kP-1) + RSV) / kP，播种 50；D = (prevD*(dP-1) + K) / dP，播种 50；J = 3K - 2D。
 * 返回与 closes 同长、全非空。highs/lows 短于 closes 时按可用值兜底。
 */
internal fun computeKDJ(
    highs: List<Double>,
    lows: List<Double>,
    closes: List<Double>,
    n: Int = 9,
    kP: Int = 3,
    dP: Int = 3,
): KdjSeries {
    val size = closes.size
    if (size == 0) return KdjSeries(emptyList(), emptyList(), emptyList())
    val k = MutableList(size) { 50.0 }
    val d = MutableList(size) { 50.0 }
    val j = MutableList(size) { 50.0 }
    var prevK = 50.0
    var prevD = 50.0
    val win = n.coerceAtLeast(1)
    for (i in 0 until size) {
        val lo = (i - win + 1).coerceAtLeast(0)
        var highN = Double.NEGATIVE_INFINITY
        var lowN = Double.POSITIVE_INFINITY
        for (idx in lo..i) {
            highN = maxOf(highN, highs.getOrElse(idx) { closes[idx] })
            lowN = minOf(lowN, lows.getOrElse(idx) { closes[idx] })
        }
        val rsv = if (highN <= lowN) 50.0 else ((closes[i] - lowN) / (highN - lowN) * 100.0)
        val curK = (prevK * (kP - 1) + rsv) / kP.coerceAtLeast(1)
        val curD = (prevD * (dP - 1) + curK) / dP.coerceAtLeast(1)
        val curJ = 3.0 * curK - 2.0 * curD
        k[i] = curK
        d[i] = curD
        j[i] = curJ
        prevK = curK
        prevD = curD
    }
    return KdjSeries(k, d, j)
}
