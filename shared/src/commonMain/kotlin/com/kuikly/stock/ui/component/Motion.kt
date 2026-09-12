// 动效驱动：循环步进、数值补间、以及它们的公共视图件。
//
// 这里放两类「需要持续变化的值」，它们都**不能**靠框架的属性动画单独完成：
//
//   1) 循环步进（[StepPulse]）—— 三点波浪、刷新旋转箭头这类「一直在动」的效果。
//      框架的 `attr.animate` 只能把某个属性从旧值插到新值，重复播放要靠
//      `repeatForever`；但 repeatForever 一旦挂上就停不下来（原生动画器会一直循环，
//      即使之后不再写值），所以「转一会儿就停」的场景用不了它。
//      这里的做法是：协程按固定间隔推进一个 observable 计数值，视图按 `step % N`
//      取模算角度/亮度，每步之间再用一个短动画补间。停的时候把值写回静止态即可。
//
//   2) 数值补间（[NumberRoll]）—— 价格/计数这类数字的滚动过渡。
//      `attr.animate` 对 `text` 属性无效（框架属性动画只支持
//      opacity / transform / backgroundColor / frame，见 core-render-android
//      的 KRCSSAnimation.supportAnimation），所以只能逐帧改写 observable 字符串。
//
// 同为动效基元的受控浮层（Overlay）在 Overlay.kt；按压态与骨架屏在 Interaction.kt。

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

/** 缓出曲线：起步快、收尾稳，比线性更接近原生手感。 */
internal fun easeOutCubic(t: Float): Float {
    val p = 1f - t
    return 1f - p * p * p
}

/**
 * 循环步进驱动：协程按间隔自增 [step]，视图侧按 `step % N` 取模算角度或亮度。
 *
 * 一次 [loop] 对应一段循环；重复调用会把上一段作废（用代次号判定），
 * 所以「用户连点两次」不会叠加出两个循环。
 */
internal class StepPulse(private val pager: Pager) {

    private var tick by pager.observable(0)
    private var generation = 0

    /** 当前步数。单调递增，视图侧自行取模。 */
    internal val step: Int get() = tick

    /**
     * 启动一段循环：[stillActive] 返回 false 时自然退出。
     * @param intervalMs 步进间隔；越小越顺、越费电。旋转类 90ms、波浪类 120ms 都够用。
     */
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

// --- AI 分析中三点波浪 ---

/** 三点波浪的步进间隔（毫秒）。8 步/秒足够顺，又不至于让协程空转太密。 */
internal const val AI_DOT_STEP_MS = 120

/** 三点波浪的亮度权重表：一个点从亮到暗再回升，形成一个循环。 */
private val AI_DOT_WAVE = floatArrayOf(1f, 0.7f, 0.42f, 0.22f, 0.38f, 0.68f)

/** 相邻两点之间的相位差（步数）。三点各错开一格，才是「依次亮起」而不是一起闪。 */
private const val AI_DOT_PHASE_STEP = 2

/** 单步过渡时长；略长于步进间隔的一半，让每步之间是补间而不是硬切。 */
private val AI_DOT_ANIMATION = Animation.easeInOut(0.16f)

private fun aiDotWeight(step: Int, index: Int): Float {
    val size = AI_DOT_WAVE.size
    val raw = (step + index) * AI_DOT_PHASE_STEP
    return AI_DOT_WAVE[((raw % size) + size) % size]
}

/**
 * 「AI 正在分析中」的三点波浪。个股详情页与指数详情页共用。
 *
 * 读 observable 与声明动画的顺序不能反：`Attr.animate` 靠 attr 块里**最近一次读到的**
 * observable 绑定动画键，顺序反了就静默失效（见 Interaction.kt 头部说明）。
 */
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

// --- 数值补间 ---

/** 数字滚动的默认时长（毫秒）。比视图动画略长：数字读完需要时间。 */
private const val NUMBER_TWEEN_MS = 300

private const val NUMBER_TWEEN_FRAME_MS = 16

/**
 * 一组数值的「滚动显示」。
 *
 * 页面持有一份，数据刷新时调 [rollTo]，视图读 [display]（整组一个字符串）
 * 或 [value]（自己格式化某个位置）——两种读法都订阅同一份滚动进度，
 * 所以同一组数字无论以什么形式出现，都是同步滚的。
 *
 * 之所以一次滚一组而不是一个：项目里这些数字都是**成组变化**的
 * （「上涨 1234 · 下跌 3456 · 平盘 678」、行情卡的「最新价/涨跌额/涨跌幅」），
 * 分开滚会各滚各的、时间对不齐，看起来是几个东西在动而不是一个面板在更新。
 *
 * @param initialText 首屏文案（数据到达前显示）。刻意不参与动画：
 *                    第一次 [rollTo] 因为尺寸对不上会退化成 [snap]，
 *                    所以「-- → 真实数字」是直接替换，不会从 0 滚上去。
 * @param format 整组格式化函数；不需要 [display] 时可以不给。
 *               每帧都会调用，必须是无副作用、能接受任意中间值的纯函数。
 */
internal class NumberRoll(
    private val pager: Pager,
    initialText: String = "",
    private val format: ((DoubleArray) -> String)? = null,
) {

    /** 当前数值组。每帧换一个新数组实例，所以 `setValue` 的相等判断不会吞掉更新。 */
    private var values by pager.observable(DoubleArray(0))

    private var text by pager.observable(initialText)
    private var generation = 0

    /** 当前显示文本。视图里直接读它。 */
    internal val display: String get() = text

    /**
     * 第 [index] 个数的当前值（滚动中即中间值）。
     *
     * 在 `attr { }` 里读它会自动订阅滚动进度——这也是为什么不用「一个预格式化的字符串」：
     * 同一组数字可能要以不同格式出现在不同位置（最新价一列、涨跌幅一列）。
     */
    internal fun value(index: Int): Double = values.getOrElse(index) { 0.0 }

    /** 直接落到目标值（首帧/不接受动画的场景）。 */
    internal fun snap(vararg v: Double) {
        generation++
        publish(DoubleArray(v.size) { v[it] })
    }

    /**
     * 从当前显示值滚到目标值。
     *
     * 尺寸不一致（第一次赋值）或数值没变时退化成 [snap] / 什么也不做，
     * 所以调用点不需要区分「首次加载」和「刷新」。
     */
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
            // 收尾对齐到精确目标值，避免浮点插值留下 0.01 的尾巴
            publish(target)
        }
    }

    private fun publish(v: DoubleArray) {
        values = v
        format?.also { text = it(v) }
    }
}
