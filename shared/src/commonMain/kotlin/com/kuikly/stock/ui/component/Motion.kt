package com.kuikly.stock.ui.component

import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.View
import com.kuikly.stock.ui.theme.AppColor

internal fun easeOutCubic(t: Float): Float {
    val p = 1f - t
    return 1f - p * p * p
}

internal class StepPulse(private val pager: Pager) {

    private var tick by pager.observable(0)
    private var generation = 0

    internal val step: Int get() = tick

    internal fun loop(intervalMs: Int, stillActive: () -> Boolean) {
        generation++
        val mine = generation
        pager.lifecycleScope.launch {
            while (mine == generation && stillActive()) {
                tick++
                delay(intervalMs)
            }
        }
    }
}

internal const val AI_DOT_STEP_MS = 120

private val AI_DOT_WAVE = floatArrayOf(1f, 0.7f, 0.42f, 0.22f, 0.38f, 0.68f)

private const val AI_DOT_PHASE_STEP = 2

private val AI_DOT_ANIMATION = Animation.easeInOut(0.16f)

private fun aiDotWeight(step: Int, index: Int): Float {
    val size = AI_DOT_WAVE.size
    val raw = (step + index) * AI_DOT_PHASE_STEP
    return AI_DOT_WAVE[((raw % size) + size) % size]
}

internal fun ViewContainer<*, *>.aiDotWaveDots(
    wave: StepPulse,
    color: Long = AppColor.PRIMARY_SOFT,
    dotSize: Float = 8f,
    gap: Float = 6f,
) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
        }
        repeat(3) { i ->
            View {
                attr {
                    width(dotSize)
                    height(dotSize)
                    borderRadius(dotSize / 2f)
                    backgroundColor(color)
                    if (i > 0) marginLeft(gap)
                    val step = wave.step
                    animate(AI_DOT_ANIMATION, step)
                    opacity(0.25f + 0.75f * aiDotWeight(step, i))
                }
            }
        }
    }
}

private const val NUMBER_TWEEN_MS = 300

private const val NUMBER_TWEEN_FRAME_MS = 16

internal class NumberRoll(
    private val pager: Pager,
    initialText: String = "",
    private val format: ((DoubleArray) -> String)? = null,
) {

    private var values by pager.observable(DoubleArray(0))

    private var text by pager.observable(initialText)
    private var generation = 0

    internal val display: String get() = text

    internal fun value(index: Int): Double = values.getOrElse(index) { 0.0 }

    internal fun snap(vararg v: Double) {
        generation++
        publish(DoubleArray(v.size) { v[it] })
    }

    internal fun rollTo(vararg v: Double) {
        val target = DoubleArray(v.size) { v[it] }
        val from = values
        if (from.size != target.size || from.contentEquals(target)) {
            snap(*v)
            return
        }
        generation++
        val mine = generation
        pager.lifecycleScope.launch {
            val frames = (NUMBER_TWEEN_MS / NUMBER_TWEEN_FRAME_MS).coerceAtLeast(1)
            for (i in 1..frames) {
                if (mine != generation) return@launch
                val t = easeOutCubic(i.toFloat() / frames)
                publish(DoubleArray(from.size) { from[it] + (target[it] - from[it]) * t })
                delay(NUMBER_TWEEN_FRAME_MS)
            }
            if (mine != generation) return@launch

            publish(target)
        }
    }

    private fun publish(v: DoubleArray) {
        values = v
        format?.also { text = it(v) }
    }
}
