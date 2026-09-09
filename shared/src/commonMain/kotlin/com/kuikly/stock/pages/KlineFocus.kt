package com.kuikly.stock.pages

/**
 * 详情页与 K 线图统一联动动作
 */
sealed interface KlineFocus {
    val label: String
    val colorValue: Long

    data class Range(
        val start: String,
        val end: String,
        override val label: String,
        override val colorValue: Long,
    ) : KlineFocus

    data class Point(
        val date: String,
        override val label: String,
        override val colorValue: Long = 0xFF5B7FFF,
    ) : KlineFocus

    data class Price(
        val value: Double,
        override val label: String,
        override val colorValue: Long,
    ) : KlineFocus
}

fun directionColorValue(direction: String?): Long = when (direction) {
    "多" -> 0xFFE64545
    "空" -> 0xFF17A67A
    else -> 0xFF8A9099
}

/**
 * K线组件焦点状态。外部设置 focus 时更新可视区间并重绘；
 * draw 末尾调用绘制覆盖层，focus 非空时定时重绘直到淡出。
 */
class KlineFocusState {
    var focus: KlineFocus? = null
    var focusSetAt: Long = 0L
    var visibleStartIdx: Int = 0
    var visibleEndIdx: Int = 0  // exclusive

    fun clearIfExpired(now: Long, ttlMs: Long = 4_000L) {
        if (focus != null && now - focusSetAt > ttlMs) focus = null
    }
}

/**
 * 给定 focus，计算需要调整到的可视区间 [startIdx, endIdx)。
 * @param dates 全量K线日期（升序）
 * @param minBars 缩放后至少显示的根数
 */
fun computeVisibleRangeFor(
    focus: KlineFocus,
    dates: List<String>,
    currentStart: Int,
    currentEnd: Int,
    minBars: Int = 30,
    padding: Int = 5,
): Pair<Int, Int> {
    fun idx(d: String) = dates.indexOf(d).takeIf { it >= 0 }
    return when (focus) {
        is KlineFocus.Range -> {
            val s = idx(focus.start) ?: return currentStart to currentEnd
            val e = idx(focus.end) ?: s
            val lo = minOf(s, e); val hi = maxOf(s, e)
            var start = lo - padding; var end = hi + padding + 1
            if (end - start < minBars) {
                val extra = (minBars - (end - start)) / 2 + 1
                start -= extra; end += extra
            }
            start.coerceAtLeast(0) to end.coerceAtMost(dates.size)
        }
        is KlineFocus.Point -> {
            val i = idx(focus.date) ?: return currentStart to currentEnd
            val span = (currentEnd - currentStart).coerceAtLeast(minBars)
            var start = i - span / 2
            var end = start + span
            if (start < 0) { end -= start; start = 0 }
            if (end > dates.size) { start -= (end - dates.size); end = dates.size }
            start.coerceAtLeast(0) to end
        }
        is KlineFocus.Price -> currentStart to currentEnd
    }
}
