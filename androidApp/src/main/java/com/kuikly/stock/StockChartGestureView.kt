package com.kuikly.stock

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.tencent.kuikly.core.render.android.expand.component.KRView
import kotlin.math.abs
import kotlin.math.hypot

class StockChartGestureView(context: Context) : KRView(context) {
    private var callback: ((Any?) -> Unit)? = null
    private var mode = "idle"
    private var downX = 0f
    private var downY = 0f
    private var span = 1f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        if (mode == "pending") {
            mode = "range"
            emit("range", "start", downX, downY)
        }
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        if (propKey == "chartGesture") {
            @Suppress("UNCHECKED_CAST")
            callback = propValue as? ((Any?) -> Unit)
            return true
        }
        return super.setProp(propKey, propValue)
    }

    private fun emit(kind: String, state: String, x: Float, y: Float, scale: Float = 1f) {
        val density = resources.displayMetrics.density
        callback?.invoke(mapOf("kind" to kind, "state" to state, "x" to x / density, "y" to y / density, "scale" to scale))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = onTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; mode = "pending"
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                removeCallbacks(longPress)
                if (mode == "range") emit("range", "end", event.x, event.y)
                mode = "pinch"
                span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)).coerceAtLeast(1f)
                emit("pinch", "start", (event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == "pending" && maxOf(abs(event.x - downX), abs(event.y - downY)) > slop) {
                    removeCallbacks(longPress)
                    if (abs(event.y - downY) > abs(event.x - downX)) {
                        mode = "vertical"
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    mode = "pan"
                    emit("pan", "start", downX, downY)
                }
                when (mode) {
                    "pan", "range" -> emit(mode, "move", event.x, event.y)
                    "pinch" -> if (event.pointerCount >= 2) {
                        val scale = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)) / span
                        emit("pinch", "move", (event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f, scale)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == "pinch") {
                emit("pinch", "end", event.x, event.y)
                mode = "finished"
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (mode == "pending" && event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    emit("tap", "end", event.x, event.y)
                } else if (mode in listOf("pan", "range", "pinch")) {
                    emit(mode, if (event.actionMasked == MotionEvent.ACTION_CANCEL) "cancel" else "end", event.x, event.y)
                }
                mode = "idle"
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDestroy() {
        removeCallbacks(longPress)
        callback = null
        parent?.requestDisallowInterceptTouchEvent(false)
        super.onDestroy()
    }
}
