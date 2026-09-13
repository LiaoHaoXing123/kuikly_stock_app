// 统一 K 线选中、区间统计与缩放状态。

package com.kuikly.stock.pages

import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class ChartCoordinateMapper(
    var visibleStartIdx: Int,
    var visibleCount: Int,
    var priceMin: Double,
    var priceMax: Double,
    var chartWidth: Float,
    var chartTop: Float,
    var chartHeight: Float,
) {
    val candleStep: Float get() = chartWidth / visibleCount.coerceAtLeast(1)
    val candleWidth: Float get() = (candleStep * 0.55f).coerceAtLeast(2f).coerceAtMost(14f)

    fun xToLocalIndex(x: Float): Int {
        if (chartWidth <= 0f || visibleCount <= 0) return 0
        return ((x / chartWidth) * visibleCount).toInt().coerceIn(0, visibleCount - 1)
    }

    fun xToGlobalIndex(x: Float): Int = visibleStartIdx + xToLocalIndex(x)

    fun localIndexToX(localIdx: Int): Float = candleStep * localIdx + candleStep / 2f

    fun globalIndexToX(globalIdx: Int): Float = localIndexToX(globalIdx - visibleStartIdx)

    fun priceToY(price: Double): Float {
        val range = priceMax - priceMin
        if (range <= 0.0) return chartTop
        return chartTop + chartHeight * ((priceMax - price) / range).toFloat()
    }

    fun yToPrice(y: Float): Double {
        if (chartHeight <= 0f) return priceMin
        val ratio = 1f - ((y - chartTop) / chartHeight).coerceIn(0f, 1f)
        return priceMin + ratio * (priceMax - priceMin)
    }

    fun zoomAt(
        anchorX: Float,
        factor: Float,
        dataSize: Int,
        minVisible: Int = 20,
        maxVisible: Int = 200,
    ): Pair<Int, Int> {
        if (dataSize <= 0) return 0 to 0
        if (!factor.isFinite() || factor <= 0f || !anchorX.isFinite()) return visibleStartIdx to visibleCount
        val anchorIdx = xToGlobalIndex(anchorX).coerceIn(0, dataSize - 1)
        val upper = maxVisible.coerceAtLeast(1).coerceAtMost(dataSize)
        val lower = minVisible.coerceAtLeast(1).coerceAtMost(upper)
        val newCount = (visibleCount / factor).toInt().coerceIn(lower, upper)
        val leftRatio = (anchorIdx - visibleStartIdx).toFloat() / visibleCount.coerceAtLeast(1)
        var newStart = (anchorIdx - (newCount * leftRatio)).toInt()
        newStart = newStart.coerceIn(0, (dataSize - newCount).coerceAtLeast(0))
        return newStart to newCount
    }

    fun pan(deltaIdx: Int, dataSize: Int): Pair<Int, Int> {
        val newStart = (visibleStartIdx + deltaIdx).coerceIn(0, (dataSize - visibleCount).coerceAtLeast(0))
        return newStart to visibleCount
    }

    fun update(
        startIdx: Int,
        count: Int,
        pMin: Double,
        pMax: Double,
        width: Float,
        top: Float,
        height: Float,
    ) {
        visibleStartIdx = startIdx
        visibleCount = count
        priceMin = pMin
        priceMax = pMax
        chartWidth = width
        chartTop = top
        chartHeight = height
    }
}

sealed interface InteractionState {
    data object Idle : InteractionState
    data class Hover(val globalIdx: Int) : InteractionState
    data class Locked(val globalIdx: Int) : InteractionState
    data class RangeSelect(val startGlobalIdx: Int, val endGlobalIdx: Int) : InteractionState
}

class CrosshairController {
    var state: InteractionState = InteractionState.Idle
        private set

    fun onMove(globalIdx: Int) {
        if (state !is InteractionState.Locked && state !is InteractionState.RangeSelect) {
            state = InteractionState.Hover(globalIdx)
        }
    }

    fun onTap(globalIdx: Int) {
        state = when (val cur = state) {

            is InteractionState.RangeSelect -> InteractionState.Idle

            is InteractionState.Locked ->
                if (cur.globalIdx == globalIdx) InteractionState.Idle else InteractionState.Locked(globalIdx)
            else -> InteractionState.Locked(globalIdx)
        }
    }

    fun onRangeStart(globalIdx: Int) {
        state = InteractionState.RangeSelect(globalIdx, globalIdx)
    }

    fun onRangeUpdate(globalIdx: Int) {
        val cur = state
        if (cur is InteractionState.RangeSelect) {
            state = cur.copy(endGlobalIdx = globalIdx)
        }
    }

    fun onRangeEnd() {
        val cur = state
        if (cur is InteractionState.RangeSelect) {
            if (abs(cur.startGlobalIdx - cur.endGlobalIdx) < 2) {

                state = InteractionState.Locked(cur.endGlobalIdx)
            }

        }
    }

    fun onLeave() {
        if (state is InteractionState.Hover) state = InteractionState.Idle
    }

    fun reset() {
        state = InteractionState.Idle
    }

    val activeIndex: Int?
        get() = when (val s = state) {
            is InteractionState.Hover -> s.globalIdx
            is InteractionState.Locked -> s.globalIdx
            is InteractionState.RangeSelect -> s.endGlobalIdx
            InteractionState.Idle -> null
        }
}

internal interface KlineInteractionHost {

    val nativeChartGestures: Boolean

    var klineStartIndex: Int
    var klineVisibleCount: Int
    var selectedKlineIndex: Int
    val klineCanvasWidth: Float
    val crosshair: CrosshairController

    fun getAggregatedKline(): List<KLineDataItem>

    fun updateCrosshair(x: Float, y: Float)
    fun tapCrosshair(x: Float)
    fun beginRangeSelect(x: Float)
    fun updateRangeSelect(x: Float)
    fun endRangeSelect()
    fun clearInteraction()
    fun clearChartSelection()

    fun selectMinuteAtX(x: Float, locked: Boolean)

    fun clearMinuteSelection()
}

internal data class RangeStats(
    val startDate: String,
    val endDate: String,
    val bars: Int,
    val changePct: Double,
    val amplitude: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val totalVolume: Double,
) {
    val summary: String
        get() = "${bars}根 涨跌${fmtSignedPct(changePct)} 振幅${fmtSignedPct(amplitude)} " +
                "高${fmt2(highPrice)} 低${fmt2(lowPrice)} 量${totalVolume.toInt()}手"
}

internal fun summarizeRange(data: List<KLineDataItem>, startIdx: Int, endIdx: Int): RangeStats? {
    if (data.isEmpty()) return null
    val lo = minOf(startIdx, endIdx).coerceIn(0, data.size - 1)
    val hi = maxOf(startIdx, endIdx).coerceIn(0, data.size - 1)
    if (lo >= hi) return null
    val slice = data.subList(lo, hi + 1)
    val first = slice.first()
    val last = slice.last()
    val changePct = if (first.open != 0.0) (last.close - first.open) / first.open * 100.0 else 0.0
    val highPrice = slice.maxOf { it.high }
    val lowPrice = slice.minOf { it.low }
    val amplitude = if (lowPrice != 0.0) (highPrice - lowPrice) / lowPrice * 100.0 else 0.0
    val totalVolume = slice.sumOf { it.volume }
    return RangeStats(
        startDate = first.tradeDate,
        endDate = last.tradeDate,
        bars = slice.size,
        changePct = changePct,
        amplitude = amplitude,
        highPrice = highPrice,
        lowPrice = lowPrice,
        totalVolume = totalVolume,
    )
}
