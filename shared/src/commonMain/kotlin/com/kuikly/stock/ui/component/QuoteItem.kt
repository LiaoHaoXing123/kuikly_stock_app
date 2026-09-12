// 行情小格：一枚标签 + 一枚加粗数值，四处排成一行，用于详情页顶部的开盘/昨收/最高/最低等。
//
// 个股页与指数页各有一份调用包装——宽度算式（卡片内外边距不同）和数字格式（万/亿、指数单位）
// 是页面自己的事；字号层级、颜色、行高这些视觉规则收在这里，两边才不会各漂一套。

package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 画一个行情小格。四格一行的宽度由调用方算好传进来（`(可用宽度 - 间隙) / 4`）。
 *
 * @param valueText 已格式化好的数值文本；为空值占位符由调用方决定（"—" / "-"）
 */
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
            // 右内边距：数值偏长时不至于贴到右边一格
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
