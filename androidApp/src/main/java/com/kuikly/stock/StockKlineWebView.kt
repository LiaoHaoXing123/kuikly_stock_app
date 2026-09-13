// 复用图表 WebView，并桥接区间和单根 K 线解读。

package com.kuikly.stock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.expand.component.KRView
import org.json.JSONObject
import kotlin.math.abs

@SuppressLint("SetJavaScriptEnabled")
class StockKlineWebView(context: Context) : KRView(context) {
    private var disposed = false
    private var payload = "{}"
    private var lastCommand = ""
    private var callback: ((Any?) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private val web = ChartBridgeHolder.acquireSharedWebView(context)

    init {
        (web.parent as? ViewGroup)?.removeView(web)
        addView(web, FrameLayout.LayoutParams(-1, -1))
        ChartBridgeHolder.onLoaded = { post { if (!disposed) render() } }
        ChartBridgeHolder.selectionListener = { data ->
            web.post { if (!disposed) callback?.invoke(data) }
        }
    }

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        "chartData" -> {
            val next = propValue.toString()
            println("[chart-data] native setProp len=${next.length} changed=${next != payload}")
            if (next != payload) { payload = next; render() }
            true
        }
        "chartSelection" -> {
            @Suppress("UNCHECKED_CAST")
            callback = propValue as? ((Any?) -> Unit)
            true
        }
        "chartCommand" -> {

            val cmd = propValue.toString()
            if (cmd != lastCommand) {
                lastCommand = cmd
                if (ChartBridgeHolder.loaded && cmd.contains("reset")) {
                    web.evaluateJavascript("window.chartCommand('reset')", null)
                }
            }
            true
        }
        else -> super.setProp(propKey, propValue)
    }

    private fun render() {
        if (ChartBridgeHolder.loaded && !disposed) {
            web.evaluateJavascript("window.renderChart(JSON.parse(${JSONObject.quote(payload)}))", null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (web.parent !== this) {
            (web.parent as? ViewGroup)?.removeView(web)
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
    }

    override fun onDestroy() {
        disposed = true; callback = null

        if (web.parent === this) removeView(web)
        super.onDestroy()
    }

    object ChartBridgeHolder {
        var shared: WebView? = null
        var loaded = false
        var onLoaded: (() -> Unit)? = null
        var selectionListener: ((Map<String, Any>) -> Unit)? = null
        private var downX = 0f
        private var downY = 0f

        fun acquireSharedWebView(context: Context): WebView {
            shared?.let { return it }
            val web = WebView(context)
            web.setBackgroundColor(Color.TRANSPARENT)
            web.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                domStorageEnabled = false
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    loaded = true
                    onLoaded?.invoke()
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    android.util.Log.d("StockKline", message.message())
                    return true
                }
            }
            web.addJavascriptInterface(bridge, "ChartBridge")
            web.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x; downY = event.y
                        web.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && abs(event.y - downY) > abs(event.x - downX) + 24) {
                        web.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> web.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            val script = context.assets.open("chart/klinecharts-9.8.12.min.js").bufferedReader().use { it.readText() }
            val html = context.assets.open("chart/index.html").bufferedReader().use { it.readText() }
            web.loadDataWithBaseURL("https://chart.local/", html.replace("/* ENGINE_BUNDLE */", script), "text/html", "UTF-8", null)
            shared = web
            return web
        }

        fun evict() {
            shared?.let { w ->
                w.removeJavascriptInterface("ChartBridge")
                w.stopLoading()
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
            shared = null
            loaded = false
            onLoaded = null
            selectionListener = null
        }

        private val bridge = object {
            @JavascriptInterface
            fun selection(value: String) {
                val parsed = runCatching { JSONObject(value) }.getOrNull() ?: return
                val kind = parsed.optString("kind")
                if (kind !in setOf("ask", "range")) return
                val from = parsed.optInt("from", -1)
                val to = parsed.optInt("to", -1)
                if (from < 0 || to < from) return
                val visibleFrom = parsed.optInt("visibleFrom", 0)
                val visibleTo = parsed.optInt("visibleTo", to + 1)
                val indicator = parsed.optString("indicator", "VOL")
                selectionListener?.invoke(mapOf("kind" to kind, "from" to from, "to" to to,
                    "visibleFrom" to visibleFrom, "visibleTo" to visibleTo, "indicator" to indicator))
            }
        }
    }
}
