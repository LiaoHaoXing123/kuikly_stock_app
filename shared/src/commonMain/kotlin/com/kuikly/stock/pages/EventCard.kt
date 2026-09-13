package com.kuikly.stock.pages

import com.kuikly.stock.data.CAL_EVENT_DIVIDEND
import com.kuikly.stock.data.CAL_EVENT_EARNINGS
import com.kuikly.stock.data.CalendarEventMark
import com.kuikly.stock.data.MarketRepository
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.views.*
import com.kuikly.stock.ui.theme.AppColor

internal fun ViewContainer<*, *>.eventCard(ctx: StockDetailPage) {
    val snap = MarketRepository.events(ctx.stockCode)
    View {
        attr { margin(4f, 12f, 4f, 12f); padding(12f); backgroundColor(AppColor.SURFACE); borderRadius(10f) }
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            Text { attr { text("公司事件"); fontSize(13f); flex(1f); color(AppColor.TEXT_DEEP) } }
            Text { attr { text(if (snap.asOf.length >= 8) "数据截至 ${snap.asOf.take(10)}" else ""); fontSize(10f); color(AppColor.TEXT_HINT) } }
        }
        if (!snap.covered) {
            Text {
                attr {
                    text("本地事件库未收录该股的事件（覆盖有限）——不代表该股没有分红或财报")
                    fontSize(11f); lineHeight(18f); color(AppColor.TEXT_SUB); marginTop(6f)
                }
            }
        } else {
            snap.upcoming.take(2).forEach { ev -> eventRow(ctx, ev, upcoming = true) }
            snap.recent.take(2).forEach { ev -> eventRow(ctx, ev, upcoming = false) }
            Text { attr { text("点击事件可在日K中定位同日行情"); fontSize(10f); color(AppColor.TEXT_HINT); marginTop(6f) } }
        }
    }
}

private fun eventKindLabel(kind: String): String = when (kind) {
    CAL_EVENT_DIVIDEND -> "除权"
    CAL_EVENT_EARNINGS -> "财报"
    else -> "事件"
}

private fun formatEventDate(raw: String): String =
    if (raw.length == 8) "${raw.take(4)}-${raw.substring(4, 6)}-${raw.takeLast(2)}" else raw.take(10)

private fun ViewContainer<*, *>.eventRow(ctx: StockDetailPage, ev: CalendarEventMark, upcoming: Boolean) {
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); marginTop(6f)
            padding(7f, 10f, 7f, 10f); borderRadius(8f)
            backgroundColor(if (upcoming) AppColor.PRIMARY_BG else AppColor.SURFACE_SOFT)
        }
        event { click { ctx.focusEvidenceDate(ev.date) } }
        View {
            attr { padding(2f, 7f, 2f, 7f); borderRadius(6f); marginRight(8f); backgroundColor(AppColor.SURFACE) }
            Text { attr { text(eventKindLabel(ev.kind)); fontSize(10f); color(AppColor.PRIMARY_SOFT) } }
        }
        Text {
            attr { text("${formatEventDate(ev.date)}  ${ev.label}"); fontSize(12f); color(AppColor.TEXT_INK); flex(1f); lineHeight(17f) }
        }
        if (upcoming) {
            Text { attr { text("未发生"); fontSize(10f); color(AppColor.TEXT_HINT) } }
        }
    }
}
