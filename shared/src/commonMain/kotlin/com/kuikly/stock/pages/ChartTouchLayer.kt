package com.kuikly.stock.pages

import com.kuikly.stock.base.HapticStyle
import com.kuikly.stock.base.hapticTick
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Stable touch target, independent of the Canvas redraw and indicator selection. */
private class ChartTouchView(private val native: Boolean) : ViewContainer<ContainerAttr, Event>() {
    override fun createAttr() = ContainerAttr()
    override fun createEvent() = Event()
    override fun viewName() = if (native) "StockChartGestureView" else ViewConst.TYPE_VIEW
}

internal fun ViewContainer<*, *>.chartTouchLayer(ctx: KlineInteractionHost, minute: Boolean = false) {
    val native = ctx.nativeChartGestures
    var startX = 0f
    var startIndex = 0
    var startCount = 0
    var inspecting = false
    var zoomMapper: ChartCoordinateMapper? = null

    fun handle(kind: String, state: String, x: Float, y: Float, scale: Float = 1f) {
        if (minute) {
            // 分时看不了缩放，但手势语义与 K 线对齐：
            //   tap         → 锁定（再点同一点取消）
            //   pan / range → 跟手查看，抬手后保留
            //   cancel      → 清除
            when (kind) {
                "tap" -> {
                    hapticTick(HapticStyle.Medium)
                    ctx.selectMinuteAtX(x, locked = true)
                }
                "pan", "range" -> when (state) {
                    "start", "move" -> ctx.selectMinuteAtX(x, locked = false)
                    "end" -> {
                        hapticTick(HapticStyle.Light)
                        ctx.selectMinuteAtX(x, locked = true)
                    }
                    else -> ctx.clearMinuteSelection()
                }
                else -> if (state == "cancel") ctx.clearMinuteSelection()
            }
            return
        }
        when (kind) {
            "tap" -> {
                hapticTick(HapticStyle.Medium)
                ctx.tapCrosshair(x)
            }
            "range" -> when (state) {
                "start" -> { hapticTick(HapticStyle.Heavy); ctx.clearInteraction(); ctx.beginRangeSelect(x) }
                "move" -> ctx.updateRangeSelect(x)
                "end" -> { hapticTick(HapticStyle.Light); ctx.updateRangeSelect(x); ctx.endRangeSelect() }
                else -> ctx.clearInteraction()
            }
            "pan" -> when (state) {
                "start" -> {
                    startX = x
                    startIndex = ctx.klineStartIndex
                    startCount = ctx.klineVisibleCount
                    inspecting = ctx.selectedKlineIndex >= 0
                    ctx.clearInteraction()
                }
                "move", "end" -> {
                    if (inspecting) {
                        // Reset Locked before moving; lock the final candle when the finger lifts.
                        ctx.crosshair.reset()
                        ctx.updateCrosshair(x, y)
                        ctx.selectedKlineIndex = ctx.crosshair.activeIndex ?: -1
                        if (state == "end") {
                            hapticTick(HapticStyle.Light)
                            ctx.crosshair.reset()
                            ctx.tapCrosshair(x)
                        }
                    } else if (ctx.klineCanvasWidth > 0f) {
                        val delta = ((x - startX) / ctx.klineCanvasWidth * startCount).toInt()
                        ctx.klineStartIndex = (startIndex - delta).coerceIn(0, (ctx.getAggregatedKline().size - startCount).coerceAtLeast(0))
                    }
                }
                else -> ctx.clearInteraction()
            }
            "pinch" -> when (state) {
                "start" -> {
                    ctx.clearChartSelection()
                    zoomMapper = ChartCoordinateMapper(ctx.klineStartIndex, ctx.klineVisibleCount, 0.0, 1.0, ctx.klineCanvasWidth, 0f, 0f)
                    startX = x
                }
                "move" -> zoomMapper?.let { mapper ->
                    val (start, count) = mapper.zoomAt(startX, scale, ctx.getAggregatedKline().size, minVisible = 10)
                    ctx.klineStartIndex = start
                    ctx.klineVisibleCount = count
                }
                else -> zoomMapper = null
            }
        }
    }

    addChild(ChartTouchView(native)) {
        attr { absolutePositionAllZero() }
        event {
            if (native) {
                register("chartGesture") { value ->
                    val p = value as? JSONObject ?: return@register
                    handle(p.optString("kind"), p.optString("state"), p.optDouble("x").toFloat(), p.optDouble("y").toFloat(), p.optDouble("scale", 1.0).toFloat())
                }
            } else {
                click { handle("tap", "end", it.x, it.y) }
                longPress { handle("range", it.state, it.x, it.y) }
                pan { handle("pan", it.state, it.x, it.y) }
                pinch { handle("pinch", it.state, it.x, it.y, it.scale) }
            }
        }
    }
}
