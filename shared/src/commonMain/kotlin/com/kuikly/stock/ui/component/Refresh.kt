// 列表通用：下拉刷新 + 触底自动加载。
//
// Kuikly 的 RefreshView / FooterRefreshView 都**不带内置 UI**：
// 高度、文案、状态机接线都要业务自己给，所以这里统一封装一份，避免每个页面重复。
//
// 两个组件的挂载约束（来自 core 2.7.0 源码）：
//   - RefreshView.scrollerView  = parent?.parent as? ScrollerView   → 必须是 Scroller 的**直接子视图**
//   - FooterRefreshView.scrollerView = 沿 parent 向上找 ScrollerView → 放哪层都能找到
// 两者都靠 Scroller 的滚动回调驱动，因此只能在 Scroller / List / WaterfallList 内使用。
//
// 接入一个页面的最小改动（三步）：
//   1. 页面持有状态：`pullState`、`refreshing`、`pullRefreshRef`，并让页面继承 BasePager
//      （`refreshSpin` 由 BasePager 提供）；
//   2. Scroller 的**第一个**子视图位置调 `pullToRefresh(...)`；
//   3. 取数结束（含失败、早退）调 `pullRefreshRef?.view?.endRefresh()`。

package com.kuikly.stock.ui.component

import com.kuikly.stock.base.HapticStyle
import com.kuikly.stock.base.hapticTick
import com.kuikly.stock.ui.theme.AppColor
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.views.FooterRefresh
import com.tencent.kuikly.core.views.FooterRefreshState
import com.tencent.kuikly.core.views.FooterRefreshView
import com.tencent.kuikly.core.views.Refresh
import com.tencent.kuikly.core.views.RefreshView
import com.tencent.kuikly.core.views.RefreshViewState
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 下拉刷新头的高度。RefreshView 缺省高度为 0，不给高度它连状态回调都不会触发。 */
internal const val PULL_HEADER_HEIGHT = 56f

/** 触底加载区高度。 */
internal const val LOAD_MORE_FOOTER_HEIGHT = 52f

/** 距底部多远开始预加载（px）。默认 100f，这里放宽到 200f 提前取数。 */
internal const val LOAD_MORE_PRELOAD = 200f

/** 刷新箭头旋转的步进间隔：12 步 × 90ms ≈ 1.1 秒一圈。 */
internal const val REFRESH_SPIN_STEP_MS = 90

private const val REFRESH_SPIN_STEPS = 12

/** 单步动画时长与步进间隔一致，视觉上就是匀速转。 */
private val REFRESH_SPIN_ANIMATION = Animation.linear(0.09f)

internal fun pullRefreshLabel(state: RefreshViewState, loading: Boolean): String = when {
    state == RefreshViewState.REFRESHING || loading -> "正在刷新…"
    state == RefreshViewState.PULLING -> "松开立即刷新"
    else -> "下拉刷新"
}

internal fun loadMoreLabel(state: FooterRefreshState, hasMore: Boolean, loading: Boolean): String = when {
    state == FooterRefreshState.NONE_MORE_DATA || !hasMore -> "没有更多数据"
    state == FooterRefreshState.FAILURE -> "加载失败，点击重试"
    state == FooterRefreshState.REFRESHING || loading -> "正在加载…"
    else -> "上拉加载更多"
}

/**
 * 在 Scroller 顶部挂一个下拉刷新头。
 *
 * 必须放在 Scroller 的**第一个**子视图位置：RefreshView 取 Scroller 用的是 `parent.parent`，
 * 中间再包一层容器就拿不到了。
 *
 * @param spin     旋转指示的步进驱动；不传就只有文字（老样子）
 * @param spinning 何时该转。**不能用 `repeatForever`**：它挂上就停不下来，
 *                 刷新结束后箭头会一直转；这里由页面在刷新开始时调
 *                 `spin.loop(REFRESH_SPIN_STEP_MS) { 刷新中标志 }`，结束自然退出。
 */
internal fun ScrollerView<*, *>.pullToRefresh(
    bind: (ViewRef<RefreshView>) -> Unit,
    label: () -> String,
    onStateChange: (RefreshViewState) -> Unit,
    onRefresh: () -> Unit,
    spin: StepPulse? = null,
    spinning: () -> Boolean = { false },
) {
    Refresh {
        ref { bind(it) }
        attr {
            height(PULL_HEADER_HEIGHT)
            allCenter()
        }
        event {
            refreshStateDidChange { state ->
                onStateChange(state)
                if (state == RefreshViewState.REFRESHING) {
                    // 触觉确认刷新真的触发了——下拉时手指盖住了刷新头，光靠文案用户看不到
                    hapticTick(HapticStyle.Medium)
                    onRefresh()
                }
            }
        }
        if (spin == null) {
            Text {
                attr {
                    text(label())
                    fontSize(12f)
                    color(AppColor.TEXT_SUB)
                }
            }
        } else {
            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                Text {
                    attr {
                        // 先读 observable 再声明动画（顺序不能反，见 Interaction.kt）
                        val step = spin.step
                        animate(REFRESH_SPIN_ANIMATION, step)
                        val active = spinning()
                        text("↓")
                        fontSize(14f)
                        color(if (active) AppColor.PRIMARY_SOFT else AppColor.TEXT_SUB)
                        marginRight(5f)
                        // 静止时指回正下方，旋转中按步取模
                        val angle = if (active) (step % REFRESH_SPIN_STEPS) * (360f / REFRESH_SPIN_STEPS) else 0f
                        transform(rotate = Rotate(angle))
                    }
                }
                Text {
                    attr {
                        text(label())
                        fontSize(12f)
                        color(AppColor.TEXT_SUB)
                    }
                }
            }
        }
    }
}

/**
 * 在列表尾部挂触底自动加载。
 *
 * 距底部不足 [LOAD_MORE_PRELOAD] 且用户拖拽过列表时，组件内部状态自动切到 REFRESHING，
 * 我们据此触发取数；取完由页面调用 `endRefresh(...)` 收尾。
 */
internal fun ViewContainer<*, *>.autoLoadFooter(
    bind: (ViewRef<FooterRefreshView>) -> Unit,
    label: () -> String,
    onStateChange: (FooterRefreshState) -> Unit,
    onLoadMore: () -> Unit,
) {
    FooterRefresh {
        ref { bind(it) }
        attr {
            height(LOAD_MORE_FOOTER_HEIGHT)
            allCenter()
            preloadDistance(LOAD_MORE_PRELOAD)
        }
        event {
            refreshStateDidChange { state ->
                onStateChange(state)
                if (state == FooterRefreshState.REFRESHING) onLoadMore()
            }
            click { onLoadMore() }
        }
        Text {
            attr {
                text(label())
                fontSize(12f)
                color(AppColor.TEXT_MUTED)
            }
        }
    }
}
