package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal fun ViewContainer<*, *>.quoteItem(
    label: String,
    valueText: String,
    width: Float,
    marginTop: Float = 8f,
) {
    View {
        attr {
            width(width)
            flexDirectionColumn()

            paddingRight(4f)
            marginTop(marginTop)
        }

        Text { attr { text(label); fontSize(AppFont.CAPTION); color(AppColor.TEXT_HINT) } }

        Text {
            attr {
                text(valueText)
                fontSize(AppFont.BODY)
                lines(1)
                marginTop(4f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
            }
        }
    }
}
