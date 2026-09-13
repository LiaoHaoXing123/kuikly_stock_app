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

internal const val PULL_HEADER_HEIGHT = 56f

internal const val LOAD_MORE_FOOTER_HEIGHT = 52f

internal const val LOAD_MORE_PRELOAD = 200f

internal const val REFRESH_SPIN_STEP_MS = 90

private const val REFRESH_SPIN_STEPS = 12

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

                        val step = spin.step
                        animate(REFRESH_SPIN_ANIMATION, step)
                        val active = spinning()
                        text("↓")
                        fontSize(14f)
                        color(if (active) AppColor.PRIMARY_SOFT else AppColor.TEXT_SUB)
                        marginRight(5f)

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
