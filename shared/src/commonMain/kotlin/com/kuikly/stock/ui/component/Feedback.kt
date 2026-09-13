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
