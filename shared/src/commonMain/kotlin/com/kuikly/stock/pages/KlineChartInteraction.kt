package com.kuikly.stock.pages

import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmtSignedPct
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// -----------------------------------------------------------------------------
// 坐标映射器：统一 x↔index、price↔y 转换，MA/AI价位线/十字光标共用
// -----------------------------------------------------------------------------
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

    /** 像素 x → 可见区间内的局部索引 */
    fun xToLocalIndex(x: Float): Int {
        if (chartWidth <= 0f || visibleCount <= 0) return 0
        return ((x / chartWidth) * visibleCount).toInt().coerceIn(0, visibleCount - 1)
    }

    /** 像素 x → 全局索引 */
    fun xToGlobalIndex(x: Float): Int = visibleStartIdx + xToLocalIndex(x)

    /** 局部索引 → 中心 x */
    fun localIndexToX(localIdx: Int): Float = candleStep * localIdx + candleStep / 2f

    /** 全局索引 → 中心 x */
    fun globalIndexToX(globalIdx: Int): Float = localIndexToX(globalIdx - visibleStartIdx)

    /** 价格 → y 像素（从上到下） */
    fun priceToY(price: Double): Float {
        val range = priceMax - priceMin
        if (range <= 0.0) return chartTop
        return chartTop + chartHeight * ((priceMax - price) / range).toFloat()
    }

    /** y 像素 → 价格 */
    fun yToPrice(y: Float): Double {
        if (chartHeight <= 0f) return priceMin
        val ratio = 1f - ((y - chartTop) / chartHeight).coerceIn(0f, 1f)
        return priceMin + ratio * (priceMax - priceMin)
    }

    /**
     * 以 anchorX 为锚点缩放，保持锚点对应的数据索引在缩放前后位置不跳变。
     * @return 新的 (visibleStartIdx, visibleCount)
     */
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

    /** 平移：deltaIdx 正=向右（看更新的数据），负=向左 */
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

// -----------------------------------------------------------------------------
// 交互状态机：Idle（默认）/ Hover（跟随）/ Locked（点击锁定）/ RangeSelect（区间框选）
// -----------------------------------------------------------------------------
internal sealed interface InteractionState {
    data object Idle : InteractionState
    data class Hover(val globalIdx: Int) : InteractionState
    data class Locked(val globalIdx: Int) : InteractionState
    data class RangeSelect(val startGlobalIdx: Int, val endGlobalIdx: Int) : InteractionState
}

internal class CrosshairController {
    var state: InteractionState = InteractionState.Idle
        private set

    /** 手指/鼠标移动：非锁定状态下跟随 */
    fun onMove(globalIdx: Int) {
        if (state !is InteractionState.Locked && state !is InteractionState.RangeSelect) {
            state = InteractionState.Hover(globalIdx)
        }
    }

    /** 点击：锁定 / 解锁 */
    fun onTap(globalIdx: Int) {
        val cur = state
        state = if (cur is InteractionState.Locked && cur.globalIdx == globalIdx) {
            InteractionState.Idle
        } else {
            InteractionState.Locked(globalIdx)
        }
    }

    /** 开始区间选择 */
    fun onRangeStart(globalIdx: Int) {
        state = InteractionState.RangeSelect(globalIdx, globalIdx)
    }

    /** 更新区间选择终点 */
    fun onRangeUpdate(globalIdx: Int) {
        val cur = state
        if (cur is InteractionState.RangeSelect) {
            state = cur.copy(endGlobalIdx = globalIdx)
        }
    }

    /** 结束区间选择 */
    fun onRangeEnd() {
        val cur = state
        if (cur is InteractionState.RangeSelect) {
            if (abs(cur.startGlobalIdx - cur.endGlobalIdx) < 2) {
                // 区间太小，视为点击锁定
                state = InteractionState.Locked(cur.endGlobalIdx)
            }
            // 否则保持 RangeSelect 状态
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

// -----------------------------------------------------------------------------
// 区间统计
// -----------------------------------------------------------------------------
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
