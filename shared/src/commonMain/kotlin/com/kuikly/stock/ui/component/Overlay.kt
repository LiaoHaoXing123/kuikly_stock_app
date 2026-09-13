// 受控浮层：显隐状态 + 入场/退场动画驱动绑在一起。
//
// 一个页面可以持有多份 Overlay（弹窗、抽屉、Toast 各一份），互不干扰。
// 侧边抽屉虽然也用 Overlay 管状态，但走的是 Drawer.kt 里的位移动画，不是这里的缩放。

package com.kuikly.stock.ui.component

import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.kuikly.stock.ui.theme.AppMotion

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
 *
 * @param exitMs 退场动画时长；`hide()` 要等它过去才卸载。抽屉比弹窗慢，用它区分。
 * @param onBack 浮层打开期间按下系统返回键的动作。传了之后浮层**挂载期间**注册到
 *               [Pager.getBackPressHandler]，BACK 会被消费（不再落到页面导航），
 *               退场动画播完、浮层卸载后自动移除。不传则不拦截 BACK（弹窗默认行为）。
 */
internal open class Overlay(
    private val pager: Pager,
    private val exitMs: Int = OVERLAY_EXIT_MS,
    onBack: (() -> Unit)? = null,
) {

    /** 挂载标志（vif 条件）。退场动画期间保持 true。 */
    private var mounted by pager.observable(false)

    /** 入场驱动：0 = 起始态。 */
    private var tick by pager.observable(0)

    /** 退场驱动：true = 已发起关闭，正在淡出。 */
    private var away by pager.observable(false)

    private var generation = 0

    /** BACK 拦截回调：挂载时注册、卸载时移除。`handleOnBackPressed` 只调 [onBack]。 */
    internal var backCallback: BackPressCallback? = null

    init {
        if (onBack != null) {
            backCallback = object : BackPressCallback() {
                override fun handleOnBackPressed() {
                    onBack()
                }
            }
        }
    }

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
        registerBack()
    }

    internal fun hide() {
        if (!mounted) return
        generation++
        val mine = generation
        away = true
        pager.lifecycleScope.launch {
            delay(exitMs)
            if (mine != generation) return@launch
            mounted = false
            away = false
            // 归位，供下次打开重放（此刻视图已卸载，不会闪）
            if (tick != 0) tick = 0
            unregisterBack()
        }
    }

    internal fun toggle() {
        if (mounted) hide() else show()
    }

    /**
     * 浮层挂载期间注册 BACK 拦截。
     *
     * 只拦截浮层打开的那段窗口：关闭（含退场动画）之后立刻交还 BACK，
     * 页面导航恢复原有返回行为，不会出现「抽屉关了 BACK 却失灵」。
     */
    private fun registerBack() {
        val cb = backCallback ?: return
        val handler = pager.getBackPressHandler()
        if (!handler.containsCallback(cb)) handler.addCallback(cb)
    }

    private fun unregisterBack() {
        val cb = backCallback ?: return
        pager.getBackPressHandler().removeCallback(cb)
    }
}

/** 浮层入场：轻微放大 + 淡入。位移会显得浮夸。 */
private val OVERLAY_ENTER_ANIMATION: Animation = Animation.easeOut(AppMotion.OVERLAY_IN_MS / 1000f)

/** 浮层退场：比入场更快，关闭要「干脆」。 */
private val OVERLAY_EXIT_ANIMATION: Animation = Animation.easeIn(AppMotion.OVERLAY_OUT_MS / 1000f)

/** 弹窗默认退场时长；卸载必须等它播完。 */
private const val OVERLAY_EXIT_MS = AppMotion.OVERLAY_OUT_MS

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
