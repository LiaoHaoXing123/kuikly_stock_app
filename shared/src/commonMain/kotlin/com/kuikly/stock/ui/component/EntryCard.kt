// 通用条目卡：白底圆角 + 一行主文案 +（可选）一行辅助说明，整块可点。
//
// 空状态的「推荐问题」「最近对话」、以及后续任何「一列可点的短条目」都是同一个壳子：
// 底色、圆角、边框、热区、按压反馈、无障碍角色在这里定一次，调用点只给文案和点击行为。

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

/**
 * 一个可点的条目卡。
 *
 * @param tag      按压反馈用的唯一标识，同一屏内不能重复
 * @param meta     第二行辅助文字；空串则不渲染
 * @param lines    主文案最多显示几行，超出省略
 */
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
