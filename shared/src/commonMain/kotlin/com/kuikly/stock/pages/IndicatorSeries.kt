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

internal data class RsiSeries(
    val rsi6: List<Double>,
    val rsi12: List<Double>,
    val rsi24: List<Double>,
)

/**
 * 单周期 RSI（Wilder 平滑）：逐日涨跌拆成 gain/loss，前 period 根用累积均值播种、
 * 其后按 Wilder 递推；RS = 平均涨 / 平均跌，RSI = 100 - 100/(1+RS)。
 * 全涨→100、全跌→0、无波动（常数价）→50（中性，避免除零）。
 * 返回与 closes 同长、全非空、值域 [0,100]；index0 无前值故播种 50。
 */
private fun rsiSeries(closes: List<Double>, period: Int): List<Double> {
    val n = closes.size
    if (n == 0) return emptyList()
    val out = DoubleArray(n) { 50.0 }
    val p = period.coerceAtLeast(1)
    var avgGain = 0.0
    var avgLoss = 0.0
    for (i in 1 until n) {
        val diff = closes[i] - closes[i - 1]
        val gain = if (diff > 0.0) diff else 0.0
        val loss = if (diff < 0.0) -diff else 0.0
        if (i <= p) {
            avgGain = (avgGain * (i - 1) + gain) / i
            avgLoss = (avgLoss * (i - 1) + loss) / i
        } else {
            avgGain = (avgGain * (p - 1) + gain) / p
            avgLoss = (avgLoss * (p - 1) + loss) / p
        }
        out[i] = when {
            avgGain == 0.0 && avgLoss == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }
    }
    return out.toList()
}

/** RSI 三线（国内 6/12/24 口径）。在完整历史上计算，调用方再按可见区间切片。 */
internal fun computeRSI(
    closes: List<Double>,
    p1: Int = 6,
    p2: Int = 12,
    p3: Int = 24,
): RsiSeries = RsiSeries(rsiSeries(closes, p1), rsiSeries(closes, p2), rsiSeries(closes, p3))

/** 一条趋势线（数据坐标：横轴为 K 线序号，纵轴为价格）。 */
internal data class TrendLine(val x1: Int, val y1: Double, val x2: Int, val y2: Double) {
    /** 线性外推到任意序号 x（把趋势线向右延伸到画布边缘时用）。 */
    fun valueAt(x: Int): Double {
        if (x2 == x1) return y2
        val slope = (y2 - y1) / (x2 - x1).toDouble()
        return y1 + slope * (x - x1)
    }
}

internal data class TrendLines(val support: TrendLine?, val resistance: TrendLine?)

/**
 * 自动趋势线：在给定（通常已切到可见区间）的 highs/lows 上找摆动高/低点
 * —— pivot 定义为在 ±window 邻域内的严格极值；压力线连最近两个摆动高、
 * 支撑线连最近两个摆动低。摆动点不足两个则对应线为 null。纯几何，无 UI 依赖。
 */
internal fun computeTrendlines(
    highs: List<Double>,
    lows: List<Double>,
    window: Int = 2,
): TrendLines {
    val n = minOf(highs.size, lows.size)
    val w = window.coerceAtLeast(1)
    if (n < 2 * w + 1) return TrendLines(null, null)
    val pivotHighs = ArrayList<Int>()
    val pivotLows = ArrayList<Int>()
    for (i in w until n - w) {
        var isHigh = true
        var isLow = true
        for (j in i - w..i + w) {
            if (j == i) continue
            if (highs[j] >= highs[i]) isHigh = false
            if (lows[j] <= lows[i]) isLow = false
        }
        if (isHigh) pivotHighs.add(i)
        if (isLow) pivotLows.add(i)
    }
    fun lineFromLastTwo(idx: List<Int>, src: List<Double>): TrendLine? {
        if (idx.size < 2) return null
        val a = idx[idx.size - 2]
        val b = idx[idx.size - 1]
        return TrendLine(a, src[a], b, src[b])
    }
    return TrendLines(
        support = lineFromLastTwo(pivotLows, lows),
        resistance = lineFromLastTwo(pivotHighs, highs),
    )
}
