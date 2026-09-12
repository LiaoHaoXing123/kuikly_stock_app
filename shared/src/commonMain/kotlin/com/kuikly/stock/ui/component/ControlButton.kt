// 图表控制小按钮：K 线图下方的「◀◀ － ＋ ▶▶」一排。
//
// 个股页与指数页原先各写了一份，唯一差别是个股页多了一下触觉反馈——
// 那种差异是漏接，不是设计，所以这里统一带上。

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

/** 32×28 的小方按钮，用于平移 / 缩放等图表操作。 */
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
