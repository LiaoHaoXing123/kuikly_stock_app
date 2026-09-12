// 通用反馈件：状态条、选择 chip、区块标题、空状态面板。
//
// 这些是「每个页面都要写一遍、写法还都不一样」的东西。收敛之后：
//   - 文案层级（标题 18 / 说明 11）只在一个地方定义；
//   - 空状态的按钮自带 44dp 热区与按压缩放，不会有的页面能点、有的页面点不动；
//   - 提示条的语义色统一走 AppColor，不再出现「同一个错误提示三种红」。

package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.kuikly.stock.ui.theme.AppRadius
import com.kuikly.stock.ui.theme.AppSize
import com.kuikly.stock.ui.theme.AppSpace
import com.kuikly.stock.ui.theme.themeTint
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 一行状态提示。`message` 为空时整块不渲染。
 *
 * 传 lambda 而不是 String，是为了让它订阅页面上的 observable：
 * 值一变整块自己重跑，调用点不用再写 `vif`。
 */
internal fun ViewContainer<*, *>.statusFeedback(message: () -> String, isError: () -> Boolean = { false }) {
    vif({ message().isNotEmpty() }) {
        View {
            attr {
                padding(12f)
                margin(8f)
                borderRadius(AppRadius.MD)
                backgroundColor(if (isError()) AppColor.DANGER_BG else AppColor.INFO_BG)
            }
            Text {
                attr {
                    text(message())
                    fontSize(AppFont.NOTE)
                    lineHeight(18f)
                    color(if (isError()) AppColor.DANGER_TEXT else AppColor.PRIMARY_TEXT)
                }
            }
        }
    }
}

/** 单选 chip。选中态用主色实底，未选中是主色浅底。 */
internal fun ViewContainer<*, *>.selectionChip(label: String, selected: () -> Boolean, action: () -> Unit) {
    View {
        attr {
            marginRight(6f)
            marginBottom(6f)
            minHeight(AppSize.TOUCH_MIN)
            allCenter()
            padding(left = 10f, top = 4f, right = 10f, bottom = 4f)
            backgroundColor(if (selected()) AppColor.PRIMARY_SOFT else AppColor.PRIMARY_BG)
            borderRadius(13f)
            accessibility("$label${if (selected()) "，已选择" else ""}")
            accessibilityRole(AccessibilityRole.CHECKBOX)
            accessibilityInfo(true, false)
        }
        event { click { action() } }
        Text {
            attr {
                text(label)
                fontSize(AppFont.NOTE)
                fontWeightBold()
                color(if (selected()) AppColor.ON_DARK else AppColor.PRIMARY_SOFT)
            }
        }
    }
}

/**
 * 区块标题：一行主标题 +（可选）一行说明。
 *
 * 首页、风险中心、详情页的各个分区都用它，标题轻重因此不会再各页不一。
 *
 * @param note     说明文字；空串则不渲染第二行
 * @param size     主标题字号。大分区用默认的 [AppFont.HEAD]，子分区可降到 [AppFont.TITLE]
 * @param marginLeft 需要与页面主内容对齐时用（例如卡片区整体左移 4dp）
 * @param onClick    非空时整块标题变为可点击（带无障碍按钮语义），用于区块标题即入口的场景
 */
internal fun ViewContainer<*, *>.sectionHeader(
    title: String,
    note: String = "",
    size: Float = AppFont.HEAD,
    color: Long = AppColor.TITLE,
    noteColor: Long = AppColor.TEXT_SUB,
    marginTop: Float = AppSpace.AIRY,
    marginBottom: Float = 10f,
    marginLeft: Float = 0f,
    onClick: (() -> Unit)? = null,
) {
    View {
        attr {
            margin(top = marginTop, left = marginLeft, bottom = marginBottom)
            if (onClick != null) {
                accessibility(title)
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
        }
        if (onClick != null) {
            event { click { onClick() } }
        }
        Text { attr { text(title); fontSize(size); fontWeightBold(); color(color) } }
        if (note.isNotEmpty()) {
            Text { attr { text(note); fontSize(AppFont.CAPTION); color(noteColor); marginTop(2f) } }
        }
    }
}

/**
 * 空状态面板：白底卡片 + 居中标题/说明 +（可选）主按钮。
 *
 * 给 [press] 才会带按压反馈；不给就是一个静态面板（用于「纯提示、无出口」的场景）。
 * 按钮点击时顺手 `releaseAll()`，防止 touchUp 丢失后按钮卡在按下态。
 */
internal fun ViewContainer<*, *>.emptyStatePanel(
    title: String,
    message: String,
    actionLabel: String? = null,
    press: PressState? = null,
    actionTag: String = "empty_action",
    onAction: (() -> Unit)? = null,
) {
    View {
        attr {
            marginTop(18f)
            padding(AppSpace.AIRY)
            borderRadius(AppRadius.LG)
            backgroundColor(AppColor.SURFACE)
            alignItems(FlexAlign.CENTER)
        }
        Text { attr { text(title); fontSize(16f); fontWeightBold(); color(AppColor.TEXT) } }
        Text {
            attr {
                text(message)
                fontSize(AppFont.NOTE)
                lineHeight(19f)
                color(AppColor.TEXT_SUB)
                marginTop(AppSpace.TIGHT)
                textAlignCenter()
            }
        }
        if (actionLabel != null && onAction != null) {
            View {
                attr {
                    marginTop(AppSpace.CARD)
                    height(AppSize.TOUCH_MIN)
                    padding(left = AppSpace.AIRY, right = AppSpace.AIRY)
                    borderRadius(AppSize.TOUCH_MIN / 2f)
                    allCenter()
                    justifyContent(FlexJustifyContent.CENTER)
                    backgroundColor(AppColor.PRIMARY)
                    if (press != null) pressedScale(press, actionTag, normal = 1f, pressed = 0.97f)
                    accessibility(actionLabel)
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event {
                    if (press != null) pressFeedback(press, actionTag)
                    click {
                        press?.releaseAll()
                        onAction()
                    }
                }
                Text {
                    attr {
                        text(actionLabel)
                        fontSize(AppFont.LABEL)
                        fontWeightBold()
                        color(Color.WHITE)
                    }
                }
            }
        }
    }
}

/**
 * 顶部居中提示浮层（Toast 风格）：绝对定位顶部、半透明底色、白字、点击整块关闭。
 *
 * 详情页 AI 提示 / 列表页提示 / AI 页错误提示三处原来是同一份结构各写一遍
 * （绝对定位 + maxWidth + 半透明底 + 圆角 + 白字），收敛成一个组件。
 *
 * 底色传 token（如 [AppColor.PRIMARY]），组件内用 [themeTint] 叠 90% alpha：
 * 切主题后取到的是当前色板对应色，不会出现旧色板穿帮。
 * 	ext 为空时整块不渲染；点击浮层任意位置触发 [onDismiss]。
 */
internal fun ViewContainer<*, *>.topToast(
    ctx: Pager,
    text: () -> String,
    onDismiss: () -> Unit,
    tint: Long = AppColor.PRIMARY,
    top: Float = 70f,
    maxWidthInset: Float = 60f,
    radius: Float = AppRadius.MD,
    hPadding: Float = 14f,
    vPadding: Float = 8f,
    fontSize: Float = AppFont.NOTE,
    lineHeight: Float? = null,
) {
    vif({ text().isNotEmpty() }) {
        View {
            attr {
                absolutePosition(top = top, left = 0f, right = 0f)
                alignItems(FlexAlign.CENTER)
            }
            event { click { onDismiss() } }
            View {
                attr {
                    maxWidth(ctx.pagerData.pageViewWidth - maxWidthInset)
                    backgroundColor(themeTint(tint))
                    borderRadius(radius)
                    padding(left = hPadding, top = vPadding, right = hPadding, bottom = vPadding)
                }
                Text {
                    attr {
                        text(text())
                        fontSize(fontSize)
                        color(AppColor.ON_DARK)
                        textAlignCenter()
                        if (lineHeight != null) lineHeight(lineHeight)
                    }
                }
            }
        }
    }
}
