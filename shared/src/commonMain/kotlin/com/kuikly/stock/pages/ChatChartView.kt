package com.kuikly.stock.pages

import com.kuikly.stock.ai.chat.chartPoints
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*
import kotlin.math.abs

/** Shared Canvas renderer; at most 120 points, no platform chart dependencies. */
internal fun ViewContainer<*, *>.chartCard(card: Map<String, Any?>) {
    val points = chartPoints(card)
    val title = card["title"] as? String ?: "走势"
    val bars = card["chart_type"] == "bar"
    val unit = card["unit"] as? String ?: ""
    View {
        attr { marginTop(10f); padding(12f); borderRadius(12f); backgroundColor(0xFFF1F6FD) }
        Text { attr { text(title); fontSize(14f); fontWeightBold(); color(0xFF203A5B) } }
        if (points.size < 2) {
            Text { attr { text("图表数据不足，请刷新行情后重试"); fontSize(12f); color(0xFF7F8998); marginTop(8f) } }
        } else {
            Text { attr { text("${points.first().label} — ${points.last().label} · ${points.size} 个交易日"); fontSize(10f); color(0xFF738399); marginTop(4f) } }
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
                    context.strokeStyle(Color(0xFFDAE4F0)); context.lineWidth(0.5f)
                    context.beginPath(); context.moveTo(left, py); context.lineTo(right, py); context.stroke()
                    context.fillStyle(Color(0xFF708198)); context.textAlign(TextAlign.RIGHT)
                    context.fillText(chartNumber(value), left - 4f, py + 3f)
                }
                context.strokeStyle(Color(0xFF1976D2)); context.fillStyle(Color(0xFF1976D2)); context.lineWidth(2f)
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
                context.fillStyle(Color(0xFF708198)); context.textAlign(TextAlign.LEFT)
                context.fillText(points.first().label.takeLast(5), left, height - 5f)
                context.textAlign(TextAlign.RIGHT)
                context.fillText(points.last().label.takeLast(5), right, height - 5f)
            }
            Text { attr { text("最新 ${chartNumber(points.last().value)}$unit · ${card["source"] ?: "行情数据"}"); fontSize(11f); color(0xFF315A86); marginTop(3f) } }
            Text { attr { text("历史行情仅供研究参考，不构成投资建议"); fontSize(10f); color(0xFF8591A0); marginTop(4f) } }
        }
    }
}

private fun chartNumber(value: Double): String = when {
    abs(value) >= 100000000 -> String.format("%.1f亿", value / 100000000)
    abs(value) >= 10000 -> String.format("%.1f万", value / 10000)
    else -> String.format("%.2f", value)
}
