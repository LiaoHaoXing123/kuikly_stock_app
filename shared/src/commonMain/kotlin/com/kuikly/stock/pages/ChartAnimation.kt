package com.kuikly.stock.pages

import com.kuikly.stock.ui.component.easeOutCubic
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.pager.Pager
import kotlin.math.roundToInt

internal const val CHART_TWEEN_MS = 200

private const val CHART_TWEEN_FRAME_MS = 16

internal fun Pager.tweenChartViewport(
    fromStart: Float,
    fromCount: Float,
    toStart: Float,
    toCount: Float,
    isCancelled: () -> Boolean = { false },
    durationMs: Int = CHART_TWEEN_MS,
    onFrame: (startIndex: Int, visibleCount: Int) -> Unit,
) {
    if (fromStart == toStart && fromCount == toCount) return
    lifecycleScope.launch {
        val frames = (durationMs / CHART_TWEEN_FRAME_MS).toInt().coerceAtLeast(1)
        for (i in 1..frames) {
            if (isCancelled()) return@launch
            val eased = easeOutCubic(i.toFloat() / frames)
            onFrame(
                (fromStart + (toStart - fromStart) * eased).roundToInt(),
                (fromCount + (toCount - fromCount) * eased).roundToInt(),
            )
            delay(CHART_TWEEN_FRAME_MS)
        }
        if (isCancelled()) return@launch

        onFrame(toStart.roundToInt(), toCount.roundToInt())
    }
}
