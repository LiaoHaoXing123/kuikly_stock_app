package com.kuikly.stock.ui.component

import com.kuikly.stock.base.HapticStyle
import com.kuikly.stock.base.hapticTick
import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal fun ViewContainer<*, *>.chartControlButton(label: String, action: () -> Unit) {
    View {
        attr {
            width(32f)
            height(28f)
            backgroundColor(AppColor.SURFACE_SOFT)
            borderRadius(8f)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            marginRight(4f)
        }
        event { click { hapticTick(HapticStyle.Light); action() } }
        Text {
            attr {
                text(label)
                fontSize(AppFont.NOTE)
                color(AppColor.TEXT_GRAY)
                textAlignCenter()
            }
        }
    }
}
