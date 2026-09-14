// 复用图表 WebView，并桥接区间和单根 K 线解读。

package com.kuikly.stock

import android.app.Activity
import android.content.ContextWrapper
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
    // 销毁后的包装视图不可复用；WebView 缓存由所属 Activity 管理。
    override val reusable: Boolean get() = false
    private var disposed = false
    private var payload = "{}"
    private var lastCommand = ""
    private var callback: ((Any?) -> Unit)? = null
    private val session = ChartBridgeHolder.acquire(context)
    private val web = session.web

    init {
        (web.parent as? ViewGroup)?.removeView(web)
        addView(web, FrameLayout.LayoutParams(-1, -1))
        session.owner = this
        session.onLoaded = { post { render() } }
        session.selectionListener = { data ->
            web.post { if (!disposed && session.owner === this) callback?.invoke(data) }
        }
    }

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        "chartData" -> {
            val next = propValue.toString()
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
                if (session.loaded && !disposed && session.owner === this && cmd.contains("reset")) {
                    web.evaluateJavascript("window.chartCommand('reset')", null)
                }
            }
            true
        }
        else -> super.setProp(propKey, propValue)
    }

    private fun render() {
        if (session.loaded && !disposed && session.owner === this && payload != "{}") {
            web.evaluateJavascript("window.renderChart(JSON.parse(${JSONObject.quote(payload)}))", null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (disposed) return
        if (web.parent !== this) {
            (web.parent as? ViewGroup)?.removeView(web)
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        post { render() }
    }

    override fun onDestroy() {
        disposed = true; callback = null
        if (session.owner === this) {
            session.owner = null
            session.onLoaded = null
            session.selectionListener = null
        }

        if (web.parent === this) removeView(web)
        super.onDestroy()
    }

    object ChartBridgeHolder {
        private val sessions = mutableMapOf<Activity, SharedChart>()

        fun acquire(context: Context): SharedChart {
            var current = context
            while (current is ContextWrapper && current !is Activity) current = current.baseContext
            val activity = current as Activity
            return sessions.getOrPut(activity) { SharedChart(activity) }
        }

        fun evict(activity: Activity) {
            sessions.remove(activity)?.destroy()
        }
    }

    class SharedChart(context: Context) {
        var owner: StockKlineWebView? = null
        val web: WebView = createWebView(context)
        var loaded = false
        var onLoaded: (() -> Unit)? = null
        var selectionListener: ((Map<String, Any>) -> Unit)? = null
        private var downX = 0f
        private var downY = 0f

        private fun createWebView(context: Context): WebView {
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
            web.addJavascriptInterface(createBridge(), "ChartBridge")
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
            return web
        }

        fun destroy() {
            owner = null
            onLoaded = null
            selectionListener = null
            web.removeJavascriptInterface("ChartBridge")
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
            loaded = false
        }

        private fun createBridge() = object {
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
