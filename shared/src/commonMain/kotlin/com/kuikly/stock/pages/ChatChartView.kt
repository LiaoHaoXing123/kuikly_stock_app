package com.kuikly.stock.pages

import com.kuikly.stock.ai.chat.chartPoints
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*
import kotlin.math.abs
import com.kuikly.stock.data.fmt1
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.ui.theme.AppColor

internal fun ViewContainer<*, *>.chartCard(card: Map<String, Any?>) {
    val points = chartPoints(card)
    val title = card["title"] as? String ?: "走势"
    val bars = card["chart_type"] == "bar"
    val unit = card["unit"] as? String ?: ""
    View {
        attr { marginTop(10f); padding(12f); borderRadius(12f); backgroundColor(AppColor.PRIMARY_BG_LIGHT) }
        Text { attr { text(title); fontSize(14f); fontWeightBold(); color(AppColor.TEXT_STRONG) } }
        if (points.size < 2) {
            Text { attr { text("图表数据不足，请刷新行情后重试"); fontSize(12f); color(AppColor.TEXT_SUB); marginTop(8f) } }
        } else {
            Text { attr { text("${points.first().label} — ${points.last().label} · ${points.size} 个交易日"); fontSize(10f); color(AppColor.TEXT_SUB_DEEP); marginTop(4f) } }
            Canvas({ attr { height(190f); marginTop(8f); accessibility("$title，最新 ${chartNumber(points.last().value)}$unit") } }) { context, width, height ->
                if (width <= 55f || height <= 40f) return@Canvas
                val left = 46f
                val right = width - 6f
                val top = 10f
                val bottom = height - 23f
                var min = if (bars) minOf(0.0, points.minOf { it.value }) else points.minOf { it.value }
                var max = if (bars) maxOf(0.0, points.maxOf { it.value }) else points.maxOf { it.value }
                if (max <= min) { val pad = maxOf(abs(max) * 0.01, 0.01); max += pad; if (!bars) min -= pad }
                val range = max - min
                fun y(value: Double) = bottom - ((value - min) / range).toFloat() * (bottom - top)
                fun x(index: Int) = if (bars) left + (right - left) * (index + 0.5f) / points.size
                    else left + (right - left) * index / (points.size - 1)
                context.font(9f)
                for (i in 0..3) {
                    val value = min + range * i / 3
                    val py = y(value)
                    context.strokeStyle(Color(AppColor.DIVIDER)); context.lineWidth(0.5f)
                    context.beginPath(); context.moveTo(left, py); context.lineTo(right, py); context.stroke()
                    context.fillStyle(Color(AppColor.TEXT_SUB)); context.textAlign(TextAlign.RIGHT)
                    context.fillText(chartNumber(value), left - 4f, py + 3f)
                }
                context.strokeStyle(Color(AppColor.PRIMARY_SOFT)); context.fillStyle(Color(AppColor.PRIMARY_SOFT)); context.lineWidth(2f)
                if (bars) {
                    val half = ((right - left) / points.size * 0.32f).coerceAtLeast(0.5f)
                    points.forEachIndexed { i, point ->
                        context.beginPath()
                        context.moveTo(x(i) - half, y(0.0)); context.lineTo(x(i) - half, y(point.value))
                        context.lineTo(x(i) + half, y(point.value)); context.lineTo(x(i) + half, y(0.0))
                        context.closePath(); context.fill()
                    }
                } else {
                    context.beginPath()
                    points.forEachIndexed { i, point -> if (i == 0) context.moveTo(x(i), y(point.value)) else context.lineTo(x(i), y(point.value)) }
                    context.stroke()
                }
                context.fillStyle(Color(AppColor.TEXT_SUB)); context.textAlign(TextAlign.LEFT)
                context.fillText(points.first().label.takeLast(5), left, height - 5f)
                context.textAlign(TextAlign.RIGHT)
                context.fillText(points.last().label.takeLast(5), right, height - 5f)
            }
            Text { attr { text("最新 ${chartNumber(points.last().value)}$unit · ${card["source"] ?: "行情数据"}"); fontSize(11f); color(AppColor.TEXT_SUB_DEEP); marginTop(3f) } }
            Text { attr { text("历史行情仅供研究参考，不构成投资建议"); fontSize(10f); color(AppColor.TEXT_SUB); marginTop(4f) } }
        }
    }
}

private fun chartNumber(value: Double): String = when {
    abs(value) >= 100000000 -> fmt1(value / 100000000) + "亿"
    abs(value) >= 10000 -> fmt1(value / 10000) + "万"
    else -> fmt2(value)
}
