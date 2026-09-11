// 图表视口补间动画。
//
// K 线/分时是 Canvas 手绘，Kuikly 的 `attr.animate(Animation, value)` 只作用于**视图属性**，
// 对 Canvas 的绘制内容无效。所以这里退一步：用协程逐帧改写 observable 视口参数，
// 由响应式系统触发 Canvas 重绘——效果与属性动画等价，且天然跨端。

package com.kuikly.stock.pages

import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.pager.Pager
import kotlin.math.roundToInt

/** 视口补间时长（毫秒）。 */
internal const val CHART_TWEEN_MS = 200

/** 每帧间隔（毫秒）。Kuikly 的 delay 接收 Int 毫秒。 */
private const val CHART_TWEEN_FRAME_MS = 16

/** 缓出曲线：起步快、收尾稳，比线性更接近原生手感。 */
private fun easeOutCubic(t: Float): Float {
    val p = 1f - t
    return 1f - p * p * p
}

/**
 * 把图表视口从 (fromStart, fromCount) 缓动到 (toStart, toCount)，逐帧回调。
 *
 * @param isCancelled 每帧询问一次；返回 true 则立刻放弃本次补间。
 *                    调用方用一个自增序号实现「后来的动画作废旧动画」，避免连点按钮时互相打架。
 * @param onFrame 每帧的目标视口，由调用方写回 observable。
 */
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
        // 收尾对齐到精确目标值，避免四舍五入留下 1 根的偏差
        onFrame(toStart.roundToInt(), toCount.roundToInt())
    }
}
