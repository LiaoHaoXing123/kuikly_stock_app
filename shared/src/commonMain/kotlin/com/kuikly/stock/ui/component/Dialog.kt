// 弹窗底部动作区：一枚次要按钮（取消）+ 可选的主按钮（确认）。
//
// 全站有 5 处「取消 / 确认」按钮对（自选页设置持仓、个股页创建价格提醒、风控页两处、
// AI 页），结构相同，底色与圆角却各写各的——自选页是 SURFACE_SOFT + 圆角 19，
// 其余是 BG_SOFT + 圆角 12，同一个动作在同一 App 里长得不一样。
//
// 这里把结构、44dp 热区、按压缩放、无障碍语义收成一份；「主按钮用什么底色」
// 这类真正的设计差异（主色 / 深色卡色）留成参数，而不是让每个调用点重画一遍。

package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.kuikly.stock.ui.theme.AppRadius
import com.kuikly.stock.ui.theme.AppSize
import com.kuikly.stock.ui.theme.AppSpace
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.views.*

/** 弹窗按钮的按压回落比例：比卡片按压（0.96）轻，按钮小，压太狠会显得抖。 */
private const val DIALOG_PRESS_SCALE = 0.97f

/** 主次按钮之间的横向间隔。 */
private const val DIALOG_BUTTON_GAP = 12f

/**
 * 弹窗里的一枚动作按钮。
 *
 * @param primary 主按钮（实底、白字）还是次按钮（浅灰底、深灰字）
 * @param press   给按压反馈；传 null 就是静态按钮（只读弹窗的「关闭」这类）
 * @param primaryColor 主按钮底色。默认主色浅底；压在深色卡上的弹窗传 [AppColor.INK_PANEL]
 * @param marginLeft 放在按钮对里时的左间距（右按钮用它和左按钮拉开）
 */
internal fun ViewContainer<*, *>.dialogActionButton(
    label: String,
    action: () -> Unit,
    primary: Boolean = false,
    press: PressState? = null,
    pressTag: String = "dialog_action",
    primaryColor: Long = AppColor.PRIMARY_SOFT,
    accessibilityText: String = label,
    marginLeft: Float = 0f,
) {
    View {
        attr {
            flex(1f)
            height(AppSize.TOUCH_MIN)
            allCenter()
            justifyContent(FlexJustifyContent.CENTER)
            borderRadius(AppRadius.MD)
            backgroundColor(if (primary) primaryColor else AppColor.BG_SOFT)
            marginLeft(marginLeft)
            accessibility(accessibilityText)
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
            if (press != null) pressedScale(press, pressTag, normal = 1f, pressed = DIALOG_PRESS_SCALE)
        }
        event {
            if (press != null) pressFeedback(press, pressTag)
            click {
                press?.releaseAll()
                action()
            }
        }
        Text {
            attr {
                text(label)
                fontSize(AppFont.LABEL)
                color(if (primary) AppColor.ON_DARK else AppColor.TEXT_SUB_DEEP)
                if (primary) fontWeightBold()
            }
        }
    }
}

/**
 * 弹窗里的一个「标签 + 输入框」。三处输入（股数 / 成本 / 提醒阈值）原先逐字重复，
 * 字号、圆角、占位色稍有漂移就很难看出来——收成一份，改一次全改。
 *
 * @param onTextChange 同步回写；`isSyncEdit = true` 是回填已有值时不要触发二次输入的开关
 * @param value 取值函数而不是 String：必须在 `attr { }` 里读 observable，页面把它改掉时
 *              输入框才会跟着变。当参数传字面量会在构建期一次性求值，之后改值不回填。
 */
internal fun ViewContainer<*, *>.dialogField(
    label: String,
    value: () -> String,
    placeholder: String,
    onTextChange: (String) -> Unit,
    marginTop: Float = AppSpace.GAP,
) {
    Text {
        attr {
            text(label)
            fontSize(AppFont.NOTE)
            color(AppColor.TEXT_SUB_DEEP)
            marginTop(marginTop)
        }
    }
    Input {
        attr {
            height(DIALOG_INPUT_HEIGHT)
            margin(top = 4f)
            fontSize(AppFont.LABEL)
            color(Color(AppColor.TEXT_INK))
            editable(true)
            text(value())
            placeholder(placeholder)
            placeholderColor(Color(AppColor.DISABLED))
            backgroundColor(AppColor.SURFACE_SOFT)
            borderRadius(AppRadius.SM)
            accessibility(label)
        }
        event { textDidChange(isSyncEdit = true) { params -> onTextChange(params.text) } }
    }
}

/** 弹窗输入框高度：比 44dp 热区矮，因为外面还有标签行，一行总高仍过 44。 */
private const val DIALOG_INPUT_HEIGHT = 36f

/**
 * 弹窗底部的标准动作区：左「取消」右「确认」。
 *
 * 两个按钮各占一半宽度，中间留 [DIALOG_BUTTON_GAP]。
 * 确认动作常常是危险操作（删除、覆盖），所以 [confirmAccessibilityText] 单独给——
 * 无障碍读到的应该是「删除这条提醒」而不是光秃秃的「确定」。
 */
internal fun ViewContainer<*, *>.dialogActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelLabel: String = "取消",
    press: PressState? = null,
    cancelTag: String = "dialog_cancel",
    confirmTag: String = "dialog_confirm",
    confirmColor: Long = AppColor.PRIMARY_SOFT,
    cancelAccessibilityText: String = cancelLabel,
    confirmAccessibilityText: String = confirmLabel,
    marginTop: Float = 14f,
) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(marginTop)
        }
        dialogActionButton(
            label = cancelLabel,
            action = onCancel,
            press = press,
            pressTag = cancelTag,
            accessibilityText = cancelAccessibilityText,
        )
        dialogActionButton(
            label = confirmLabel,
            action = onConfirm,
            primary = true,
            press = press,
            pressTag = confirmTag,
            primaryColor = confirmColor,
            accessibilityText = confirmAccessibilityText,
            marginLeft = DIALOG_BUTTON_GAP,
        )
    }
}
