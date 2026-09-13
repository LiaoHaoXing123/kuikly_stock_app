package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun ViewContainer<*, *>.navIcon(route: String, selected: Boolean) {
    Canvas({ attr { size(24f, 24f) } }) { context, _, _ ->
        context.strokeStyle(Color(if (selected) AppColor.PRIMARY else AppColor.TEXT_SUB))
        context.lineWidth(1.8f)
        context.lineCapRound()
        fun path(vararg points: Pair<Float, Float>) {
            context.beginPath()
            points.forEachIndexed { i, p ->
                if (i == 0) context.moveTo(p.first, p.second) else context.lineTo(p.first, p.second)
            }
            context.stroke()
        }
        when (route) {
            AppRoutes.HOME -> {
                path(3f to 10f, 12f to 3f, 21f to 10f)
                path(5f to 9f, 5f to 21f, 10f to 21f, 10f to 15f, 14f to 15f, 14f to 21f, 19f to 21f, 19f to 9f)
            }
            AppRoutes.MARKET -> {
                path(4f to 3f, 4f to 21f, 21f to 21f)
                path(7f to 16f, 11f to 11f, 15f to 14f, 21f to 6f)
            }
            AppRoutes.CHAT -> {
                path(4f to 4f, 20f to 4f, 20f to 17f, 12f to 17f, 7f to 21f, 7f to 17f, 4f to 17f, 4f to 4f)
                path(8f to 9f, 16f to 9f)
                path(8f to 13f, 13f to 13f)
            }
            AppRoutes.WATCHLIST -> {
                val points = (0..10).map { i ->
                    val angle = -PI / 2 + i * PI / 5
                    val radius = if (i % 2 == 0) 10f else 4.7f
                    (12f + cos(angle).toFloat() * radius) to (12f + sin(angle).toFloat() * radius)
                }
                path(*points.toTypedArray())
            }
            AppRoutes.PROFILE -> {
                context.beginPath()
                context.arc(12f, 7f, 4f, 0f, (2 * PI).toFloat(), false)
                context.stroke()
                context.beginPath()
                context.moveTo(4f, 21f)
                context.bezierCurveTo(4f, 12f, 20f, 12f, 20f, 21f)
                context.stroke()
            }
        }
    }
}
