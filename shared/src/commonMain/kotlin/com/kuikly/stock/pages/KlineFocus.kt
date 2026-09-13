package com.kuikly.stock.pages
import com.kuikly.stock.data.StockColors
import com.kuikly.stock.ui.theme.AppColor

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
        override val colorValue: Long = AppColor.ACCENT,
    ) : KlineFocus

    data class Price(
        val value: Double,
        override val label: String,
        override val colorValue: Long,
    ) : KlineFocus
}

fun directionColorValue(direction: String?): Long = when (direction) {
    "多" -> StockColors.UP
    "空" -> StockColors.DOWN
    else -> AppColor.NEUTRAL
}

class KlineFocusState {
    var focus: KlineFocus? = null
    var focusSetAt: Long = 0L
    var visibleStartIdx: Int = 0
    var visibleEndIdx: Int = 0

    fun clearIfExpired(now: Long, ttlMs: Long = 4_000L) {
        if (focus != null && now - focusSetAt > ttlMs) focus = null
    }
}

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
