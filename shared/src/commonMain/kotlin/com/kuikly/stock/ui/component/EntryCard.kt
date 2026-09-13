package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.kuikly.stock.ui.theme.AppRadius
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal fun ViewContainer<*, *>.entryCard(
    press: PressState,
    tag: String,
    title: String,
    meta: String = "",
    titleColor: Long = AppColor.PRIMARY,
    lines: Int = 2,
    accessibilityLabel: String = title,
    onClick: () -> Unit,
) {
    View {
        attr {
            marginTop(8f)
            padding(left = 14f, top = 12f, right = 14f, bottom = 12f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(AppRadius.MD)
            border(Border(1f, BorderStyle.SOLID, Color(AppColor.DIVIDER)))
            pressedBg(press, tag, normal = AppColor.SURFACE)
            pressedScale(press, tag, normal = 1f, pressed = 0.98f)
            accessibility(accessibilityLabel)
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event {
            pressFeedback(press, tag)
            click {
                press.releaseAll()
                onClick()
            }
        }
        Text {
            attr {
                text(title)
                fontSize(AppFont.BODY)
                color(titleColor)
                lines(lines)
            }
        }
        if (meta.isNotEmpty()) {
            Text {
                attr {
                    text(meta)
                    fontSize(AppFont.CAPTION)
                    color(AppColor.TEXT_HINT)
                    marginTop(3f)
                    lines(1)
                }
            }
        }
    }
}
