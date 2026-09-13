// 侧边抽屉：遮罩淡入 + 面板从侧边滑入/滑出。
//
// 【为什么不是弹窗】
// 原先历史对话用的是 Overlay 的「缩放 0.94→1 + 淡入」。那个表现在语义上是「浮层从原地
// 弹出来」，属于对话框；而抽屉在空间隐喻上是「从屏幕外滑进来的一块面板」，
// 用缩放表达会让人以为它是浮在当前页上方的弹窗，和「点右上角拉开历史」这个动作对不上。
//
// 【技术约束（别踩）】
//   - 位移只能用 `Translate(percentageX=...)`：`offsetX` 在序列化成原生 prop 时会被丢掉
//     （`Translate.toString()` 只输出两个百分比，见 core 的 Attr.kt）。
//   - 百分比是**相对元素自身宽度**的，所以 ±100 就正好把面板推出屏幕，与抽屉多宽无关。
//     这是本组件不需要知道「屏幕宽度」也能滑干净的原因。
//   - 与 Overlay.kt 一样，读 observable 和声明动画的顺序不能反，且必须在 `attr { }` 里调。

package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppMotion
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.View
import com.kuikly.stock.ui.theme.AppColor

/** 抽屉挂在屏幕哪一侧。决定滑入方向，也决定面板的定位边。 */
internal enum class DrawerSide { LEFT, RIGHT }

/** 遮罩底色：40% 黑。够压住背景，又不会让下层内容完全看不见。 */
private const val SCRIM_COLOR: Long = 0x66000000

/** 默认抽屉宽度占屏宽比例。留出一点背景，用户能看出「这是盖在当前页上的」。 */
private const val DEFAULT_DRAWER_WIDTH_RATIO = 0.82f

/** 面板滑入：比弹窗慢一点，位移需要时间被看清。 */
private val DRAWER_ENTER_ANIMATION: Animation = Animation.easeOut(AppMotion.DRAWER_IN_MS / 1000f)

/** 面板滑出：比滑入快，关闭要「干脆」。 */
private val DRAWER_EXIT_ANIMATION: Animation = Animation.easeIn(AppMotion.DRAWER_OUT_MS / 1000f)

/** 遮罩淡入：略快于面板，让焦点先落到面板上。 */
private val SCRIM_ENTER_ANIMATION: Animation = Animation.easeOut(AppMotion.SCRIM_MS / 1000f)

/** 遮罩淡出。 */
private val SCRIM_EXIT_ANIMATION: Animation = Animation.easeIn(AppMotion.SCRIM_MS / 1000f)

/**
 * 建一个抽屉专用的 [Overlay]。
 *
 * 与弹窗的区别只在退场时长：`Overlay.hide()` 要等退场动画播完才卸载视图，
 * 抽屉滑出比弹窗淡出慢，用默认的 130ms 会在半路上把面板摘掉（看起来是「闪没了」）。
 *
 * 抽屉同时绑定系统返回键：打开期间按 BACK 只关抽屉，不退回页面；
 * 抽屉关掉后 BACK 恢复原导航行为（见 [Overlay.onBack]）。
 */
internal fun drawerState(pager: Pager): Overlay =
    Overlay(pager, AppMotion.DRAWER_OUT_MS, onBack = null).apply {
        backCallback = object : BackPressCallback() {
            override fun handleOnBackPressed() {
                hide()
            }
        }
    }

/**
 * 遮罩的进场/退场：只做淡入淡出，不位移。
 *
 * 与面板分开写，是因为两者动画时长不同（遮罩 180ms / 面板 260ms），
 * 合成一个 attr 块就只能取其一。
 */
internal fun Attr.scrimEnterExit(drawer: Overlay) {
    val entering = drawer.entering
    animate(SCRIM_ENTER_ANIMATION, entering)
    val leaving = drawer.leaving
    animate(SCRIM_EXIT_ANIMATION, leaving)
    opacity(if (entering || leaving) 0f else 1f)
}

/**
 * 面板的进场/退场：横向位移。
 *
 * `entering`（刚挂载）与 `leaving`（正在关闭）都落在「滑出屏幕」这个视觉端点上，
 * 区别只在于用哪份动画走过去——与 Overlay.kt 的 `overlayEnterExit` 同一套写法。
 */
internal fun Attr.drawerEnterExit(drawer: Overlay, side: DrawerSide) {
    val entering = drawer.entering
    animate(DRAWER_ENTER_ANIMATION, entering)
    val leaving = drawer.leaving
    animate(DRAWER_EXIT_ANIMATION, leaving)
    val offScreen = when (side) {
        DrawerSide.LEFT -> -100f
        DrawerSide.RIGHT -> 100f
    }
    transform(translate = Translate(percentageX = if (entering || leaving) offScreen else 0f))
}

/**
 * 挂一个侧边抽屉。**放在页面内容之后**（同级层叠，靠 absolute 覆盖在上面）。
 *
 * ```
 * vif({ ctx.drawer.isVisible }) {
 *     sideDrawer(
 *         ctx.drawer,
 *         pageWidth = ctx.pagerData.pageViewWidth,
 *         side = DrawerSide.LEFT,
 *         onScrimTap = { ctx.drawer.hide() },
 *     ) {
 *         // 抽屉内部内容
 *     }
 * }
 * ```
 *
 * 系统返回键由 [drawerState] 统一绑定为「关抽屉」，这里不用再传。
 *
 * @param pageWidth   屏幕宽度（`pagerData.pageViewWidth`）。抽屉宽度 = pageWidth × [widthRatio]
 * @param side        从哪一侧滑入；面板也贴那一侧
 * @param widthRatio  抽屉宽度占屏宽比例
 * @param scrimColor  遮罩底色
 * @param onScrimTap  点遮罩的动作，通常就是关抽屉
 * @param content     抽屉内容。注意它**不含**顶部状态栏留白，需要的话自己加
 */
internal fun ViewContainer<*, *>.sideDrawer(
    drawer: Overlay,
    pageWidth: Float,
    side: DrawerSide = DrawerSide.LEFT,
    widthRatio: Float = DEFAULT_DRAWER_WIDTH_RATIO,
    scrimColor: Long = SCRIM_COLOR,
    onScrimTap: () -> Unit,
    content: ViewContainer<*, *>.() -> Unit,
) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(scrimColor)
            scrimEnterExit(drawer)
        }
        // 点遮罩关抽屉：这是抽屉最容易预期到的关闭手势，比只留一个「收起」按钮自然
        event { click { onScrimTap() } }

        View {
            attr {
                drawerEnterExit(drawer, side)
                when (side) {
                    DrawerSide.LEFT -> absolutePosition(top = 0f, left = 0f, bottom = 0f)
                    DrawerSide.RIGHT -> absolutePosition(top = 0f, bottom = 0f, right = 0f)
                }
                width(pageWidth * widthRatio)
                backgroundColor(AppColor.SURFACE)
                flexDirectionColumn()
            }
            // 面板上刻意**不注册** click：Kuikly 的 click 不向上冒泡，点面板空白处不会误触遮罩的
            // 关闭逻辑；反过来给它挂一个空 handler 会多一个触摸拦截层，可能干扰内部 Scroller 的手势。
            content()
        }
    }
}
