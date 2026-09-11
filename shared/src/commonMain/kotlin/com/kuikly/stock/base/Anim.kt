// 循环动效与数值补间。
//
// 这里放三类「需要持续变化的值」，它们都不能靠框架的属性动画单独完成：
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
//   3) AI 三点波浪的公共件（[_aiDotWeight] / [aiDotWaveDots]）—— 个股页与指数页共用，
//      避免两处各写一份相位表。

package com.kuikly.stock.base

import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.View

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
    color: Long = 0xFF1976D2,
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
 * 一个受控浮层：显隐状态 + 入场/退场动画驱动绑在一起。
 *
 * 为什么合成一个对象：动画要求「先挂载、后翻转驱动值」，
 * 如果显隐和驱动值分两个字段，调用点就可能只改一半——漏了翻转的后果不是「没有动画」，
 * 而是弹窗**停在起始态（透明）**，看起来像点坏了。合在一起之后调用点只能写
 * [show] / [hide] / [toggle]，不可能只改一半。
 *
 * 退场为什么不能直接把 vif 条件置 false：视图会被同步卸载，动画没有播放的载体。
 * 所以 [hide] 先只翻 `leaving` 让视图淡出，**等退场时长过去**再把挂载标志收回。
 * 期间若用户又点开（[show]），代次号会让挂起的卸载作废，不会把刚打开的浮层关掉。
 *
 * 一个浮层一个实例。共用一份驱动值会在「A 开着时关掉 B」的瞬间把 A 也拉回起始态。
 */
internal class Overlay(private val pager: Pager) {

    /** 挂载标志（vif 条件）。退场动画期间保持 true。 */
    private var mounted by pager.observable(false)

    /** 入场驱动：0 = 起始态。 */
    private var tick by pager.observable(0)

    /** 退场驱动：true = 已发起关闭，正在淡出。 */
    private var away by pager.observable(false)

    private var generation = 0

    /** 供 vif 判断是否挂载。 */
    internal val isVisible: Boolean get() = mounted

    /** 供浮层根节点判断是否播放进场（true = 起始态：透明 + 缩小）。 */
    internal val entering: Boolean get() = tick == 0

    /** 供浮层根节点判断是否播放退场。 */
    internal val leaving: Boolean get() = away

    internal fun show() {
        // 作废挂起的卸载：淡出还没走完就又点开了，应该原地转回可见
        generation++
        away = false
        mounted = true
        tick++
    }

    internal fun hide() {
        if (!mounted) return
        generation++
        val mine = generation
        away = true
        pager.lifecycleScope.launch {
            delay(OVERLAY_EXIT_MS)
            if (mine != generation) return@launch
            mounted = false
            away = false
            // 归位，供下次打开重放（此刻视图已卸载，不会闪）
            if (tick != 0) tick = 0
        }
    }

    internal fun toggle() {
        if (mounted) hide() else show()
    }
}

/** 浮层入场：轻微放大 + 淡入。位移会显得浮夸。 */
private val OVERLAY_ENTER_ANIMATION: Animation = Animation.easeOut(0.16f)

/** 浮层退场：比入场更快，关闭要「干脆」。 */
private val OVERLAY_EXIT_ANIMATION: Animation = Animation.easeIn(0.12f)

/** 退场动画时长；卸载必须等它播完。 */
private const val OVERLAY_EXIT_MS = 130

private const val OVERLAY_SCALE = 0.94f

/**
 * 浮层容器的进场/退场表现：透明 + 缩放到位。
 *
 * **必须在 `attr { }` 里调用**——动画绑定靠「attr 块里最近一次读到的 observable」，
 * 在别处调用会静默失效。进场和退场各注册一份动画（两个不同的 observable 键），
 * 框架按「本次变化的是哪个键」挑对应那份。
 */
internal fun Attr.overlayEnterExit(overlay: Overlay, scaleFrom: Float = OVERLAY_SCALE) {
    val entering = overlay.entering
    animate(OVERLAY_ENTER_ANIMATION, entering)
    val leaving = overlay.leaving
    animate(OVERLAY_EXIT_ANIMATION, leaving)
    // 起始态和退场态是同一个视觉端点，区别只在于用哪份动画走过去
    val hidden = entering || leaving
    opacity(if (hidden) 0f else 1f)
    val s = if (hidden) scaleFrom else 1f
    transform(scale = Scale(s, s))
}

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
