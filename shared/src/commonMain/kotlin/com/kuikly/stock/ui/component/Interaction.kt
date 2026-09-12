// 交互反馈基元：按压态 + 骨架屏（含扫光）。
//
// 解决什么：这两个是「原型感」最直接的来源——
//   1) 全仓 150 处 click，按下瞬间视觉上零变化，用户只能靠松手后的跳转反推「刚才点到了」；
//   2) 加载态都是一行「加载中...」文字，数据到达前后版式完全不同，页面会跳。
//
// 实现依据（都用框架自身能力，不自造轮子）：
//
//   按压态 —— `View` 的事件对象是 `DivEvent : GroupEvent`，`GroupEvent` 公开了
//   `touchDown/touchUp/touchCancel` 三个触摸回调；同时 `DeclarativeBaseView.attr()`
//   内部用 `ReactiveObserver.bindValueChange` 把整个 attr 块包了起来，所以 attr 块里
//   读到的 observable 一旦变化，attr 块会重跑并把新值下发成 native prop。
//   两者一结合，就是「按下换底色、抬起还回来」的最小闭环，不需要新建 Module。
//
//   骨架屏 —— 灰色圆角块，价值不在好看，在于**先把版式占住**：数据到达前后
//   元素位置不变，眼睛不用重新找焦点。
//
//   扫光 —— 在骨架块里叠一条渐变带，用 `attr.animate(Animation.repeatForever)` 让它横向扫过。
//   框架的属性动画只认四种 prop：opacity / transform / backgroundColor / frame
//   （见 core-render-android 的 KRCSSAnimation.supportAnimation），所以位移必须走 transform；
//   而 `Translate` 的 offsetX 序列化时会被丢掉（toString 只输出百分比），
//   故扫光用**百分比位移**：百分比是相对元素自身宽度的，带宽固定 90f 时
//   percentageX=6 就是向右 540f，足够扫过任何一屏宽的骨架块。
//
//   扫光的驱动值必须是「挂载之后才翻转」的 observable：attr 块首帧走的是
//   `isFirst=true` 分支，不加动画；只有后续变更才会带上 ANIMATION prop。
//   所以由页面在骨架屏挂载后调用 [SkeletonSweep] 的持有者（`MountPulse.bump()`），
//   并且**静态兜底必须是不可见的**——起点在块左外侧、终点在块右外侧，
//   万一动画没跑起来也只是「没有扫光」，不会留下脏色块。
//
// 纪律：
//   - 按压态只改「表现」，绝不改业务状态，也不动 click 的既有逻辑；
//   - 同一时刻只允许一个元素处于按下态（单指触摸的天然约束），所以用一个
//     全局 key 表达即可，不必给每个元素各配一份状态；
//   - **按压底色/缩放不做属性动画**：`Attr.animate` 在「按下→抬起」这条反向路径上
//     会漏掉 prop 更新，元素会永久停在按下态（详情页顶栏刷新按钮一直顶着一块浅蓝底，
//     就是这个原因——按下那一下渲染出来了，抬起那一下没渲染回去）。
//     原生按钮的按压态本来就是硬切，不做过渡也不损失什么。

package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppMotion
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.internal.GroupEvent

/** 中性面按下时的高亮底色。接主题：深色底上换成极淡的白，避免整块跳亮。 */
internal val PRESS_BG_LIGHT: Long get() = AppColor.PRESS_BG

/** 深色/彩色底按下时的高亮底色。
 *  注意必须显式标 Long：0x33FFFFFF 落在 Int 范围内，不标会被推成 Int。 */
internal const val PRESS_BG_DARK: Long = 0x33FFFFFF

/** 骨架块底色。接主题：深色底上要换成「比卡片略亮」，浅灰会刺眼。 */
internal val SKELETON_BG: Long get() = AppColor.SKELETON_BG

/** 骨架块上的次级元素（文字行）底色，比底色再深一档，用来区分结构。 */
internal val SKELETON_BG_STRONG: Long get() = AppColor.SKELETON_BG_STRONG

/** 深色/彩色底上的骨架块（例如顶栏里的占位），用半透明白才看得见。 */
internal const val SKELETON_BG_ON_DARK: Long = 0x59FFFFFF

/** 「无底色」：可点元素常态不需要底色时用它，避免 Int/Long 推断歧义。 */
internal const val PRESS_BG_NONE: Long = 0x00FFFFFF

/** 按下时的缩放比例。0.96 是 iOS/Android 原生按钮反馈的常见幅度：能感觉到，但不跳。 */
internal const val PRESS_SCALE = AppMotion.PRESS_SCALE

// --- 骨架屏扫光参数 ---

/** 扫光带宽（dp）。这是百分比位移的基准，改它要同步改 [SHIMMER_START] / [SHIMMER_END]。 */
private const val SHIMMER_BAND_WIDTH = 90f

/** 扫光起点：-1.2 个带宽，整条带子停在骨架块左外侧（静态兜底时不可见）。 */
private const val SHIMMER_START = -1.2f

/** 扫光终点：6 个带宽 = 540dp，足以扫过任何一屏宽的骨架块，停住时已在右外侧。 */
private const val SHIMMER_END = 6f

private val SHIMMER_ANIMATION: Animation = Animation.linear(1.35f).repeatForever(true)

/**
 * 「挂载脉冲」：给「刚出现的视图」做入场/循环动画用的驱动值。一个用途一份实例。
 *
 * 为什么必须是 observable 且必须在挂载后自增：attr 块首帧走的是 `isFirst=true` 分支，
 * 不带 ANIMATION prop（见 DeclarativeBaseView.attr）；只有后续变更才会走动画路径。
 * 所以「视图先挂载、再自增」这个顺序不能颠倒。
 *
 * 两种用法共用这一个计数器：
 *   - **循环动画**（骨架屏扫光）：按奇偶映射到两个**都不可见**的端点，
 *     每次 [bump] 都真的变化、都重新起一段动画，方向左右交替也仍然是「活的」；
 *     万一某次没自增，只是没有动画，不会留下脏色块。
 *   - **单向入场**（弹窗）：`generation == 0` 是起始态（透明/缩小），>0 是结束态。
 *     只在挂载后自增一次，关闭时用 [settle] 归零，下一次打开才能重放。
 */
internal class MountPulse(scope: PagerScope) {

    private var tick by scope.observable(0)

    /** 当前世代。骨架扫光看奇偶，浮层入场看是否为 0。 */
    internal val generation: Int get() = tick

    /** 推进一步，触发绑定了本计数器的属性动画。 */
    internal fun bump() {
        tick++
    }

    /** 归位到起始态，供下一次挂载使用。视图已卸载时调用最安全。 */
    internal fun settle() {
        if (tick != 0) tick = 0
    }
}

/**
 * 按压态容器。一个页面持有一个即可。
 *
 * 为什么不给每个元素单独配状态：手指只有一个，同一时刻只可能有一个元素被按下，
 * 用「当前被按下的 key」这一个值就够了。这样按下时只会重跑两处 attr 块
 * （旧的恢复、新的高亮），不会把整个列表刷一遍。
 *
 * 状态只有「按下」「抬起」两个事件驱动，抬手时由 [release] 清空。
 * 注意：高亮的**显示**必须是硬切，不能给它套属性动画——见 [pressedBg] 的说明，
 * 动画会让反向（抬起）那次 prop 更新丢失，高亮就永久留在元素上。
 */
internal class PressState(scope: PagerScope) {

    private var key: String by scope.observable("")

    /** 该元素当前是否处于按下态。 */
    internal fun isPressed(tag: String): Boolean = key == tag

    internal fun press(tag: String) {
        if (key != tag) key = tag
    }

    internal fun release(tag: String) {
        // 只清自己那一份：快速连续点击时，后一个元素的 touchDown 可能先于
        // 前一个元素的 touchUp 到达，无条件清空会把新按下的元素一起抹掉。
        if (key == tag) key = ""
    }

    /** 兜底：页面切换/滚动中断时把按下态清干净，避免元素卡在高亮上。 */
    internal fun releaseAll() {
        if (key.isNotEmpty()) key = ""
    }
}

/**
 * 在 `attr { }` 里声明按压底色。读的是 [PressState]，所以按下/抬起会自动重跑 attr 块。
 *
 * **不做过渡动画**（曾经的 `animate(Animation.easeOut(0.12f))` 已删除）：
 * `Attr.animate` 只在「值变化」这条路径上把 prop 交给动画驱动，而按压态是
 * 「按下一次、抬起一次」的往返更新——反向那一次（抬起）的 prop 更新会丢，
 * 元素就永久停在按下底色上。表现最典型的就是详情页顶栏刷新按钮：
 * 点一下（或长按一下）之后，蓝底上永远贴着一块 20% 白的浅蓝高亮。
 * 原生按钮的按压态本来就是硬切，去掉过渡反而更接近系统手感。
 *
 * 用法：
 * ```
 * View {
 *     attr {
 *         pressedBg(ctx.press, "row:$code", normal = AppColor.SURFACE)
 *     }
 *     event { pressFeedback(ctx.press, "row:$code") }
 * }
 * ```
 */
internal fun Attr.pressedBg(
    press: PressState,
    tag: String,
    normal: Long,
    pressed: Long = PRESS_BG_LIGHT,
) {
    backgroundColor(if (press.isPressed(tag)) pressed else normal)
}

/**
 * 在 `attr { }` 里声明按压缩放。主按钮（AI 分析、重试这类）加上它才「按得动」。
 *
 * 与 [pressedBg] 可以同时用，两者都是硬切（原因见 [pressedBg] 的说明）。
 */
internal fun Attr.pressedScale(
    press: PressState,
    tag: String,
    normal: Float = 1f,
    pressed: Float = PRESS_SCALE,
) {
    val s = if (press.isPressed(tag)) pressed else normal
    transform(scale = Scale(s, s))
}

/**
 * 在 `event { }` 里注册按压三段式：按下 / 抬起 / 取消（被滚动手势打断等）。
 *
 * 刻意不发触觉反馈：调用点的 `click` 里已经有 `hapticTick`，
 * 这里再发一次会变成「按下震一下、松手又震一下」的双重反馈。
 */
internal fun GroupEvent.pressFeedback(press: PressState, tag: String) {
    touchDown { press.press(tag) }
    touchUp { press.release(tag) }
    touchCancel { press.release(tag) }
}

/**
 * 骨架块：一个灰色圆角占位，用来在数据到达前把版式撑住。
 *
 * @param height  高度（必填，这是占位的核心信息）
 * @param w       宽度；传 null（默认）表示横向占满可用空间
 * @param radius  圆角
 * @param color   底色，可用 [SKELETON_BG_STRONG] 表达次级元素
 * @param sweep   扫光驱动；传 null（默认）就是静态灰块
 */
internal fun ViewContainer<*, *>.skeletonBlock(
    height: Float,
    w: Float? = null,
    radius: Float = 4f,
    color: Long = SKELETON_BG,
    sweep: MountPulse? = null,
) {
    View {
        attr {
            if (w != null) {
                width(w)
            } else {
                flex(1f)
            }
            this.height(height)
            borderRadius(radius)
            backgroundColor(color)
        }
        if (sweep != null) {
            skeletonShimmerBand(sweep, radius)
        }
    }
}

/**
 * 骨架块里的扫光带。
 *
 * 位置固定在父块左内侧（left=0），靠 percentageX 位移扫出去；带宽固定，
 * 所以百分比与 dp 的换算与父块宽度无关。
 *
 * 圆角父块会裁剪子视图（框架文档明示：设置圆角后子孩子无法超出自身区域），
 * 这正是扫光「只在块内可见」所依赖的行为。
 */
private fun ViewContainer<*, *>.skeletonShimmerBand(sweep: MountPulse, radius: Float) {
    View {
        attr {
            val generation = sweep.generation
            animate(SHIMMER_ANIMATION, generation)
            absolutePosition(top = 0f, left = 0f, bottom = 0f)
            width(SHIMMER_BAND_WIDTH)
            borderRadius(radius)
            backgroundLinearGradient(
                Direction.TO_RIGHT,
                ColorStop(Color(0x00FFFFFFL), 0f),
                ColorStop(Color(0x66FFFFFFL), 0.5f),
                ColorStop(Color(0x00FFFFFFL), 1f),
            )
            val x = if (generation % 2 == 0) SHIMMER_START else SHIMMER_END
            transform(translate = Translate(percentageX = x))
        }
    }
}
