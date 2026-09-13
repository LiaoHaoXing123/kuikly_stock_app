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

private const val DIALOG_PRESS_SCALE = 0.97f

private const val DIALOG_BUTTON_GAP = 12f

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

private const val DIALOG_INPUT_HEIGHT = 36f

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
