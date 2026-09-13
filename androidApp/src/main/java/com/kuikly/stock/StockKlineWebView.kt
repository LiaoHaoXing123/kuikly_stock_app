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

/**
 * Only packaged chart code is loaded; the bridge carries chart selection, never credentials.
 *
 * 进程内只保留一个共享 WebView：切换专业图/基础图时 Kuikly 会销毁并重建本视图，
 * 若每次都新建并销毁 WebView，旧图销毁会让系统回收 Chromium 渲染进程，紧随其后
 * 创建的新图会白屏（Renderer process crash detected）。共享实例只加载一次引擎，
 * 重建时直接换挂到新视图上，图表状态（画线、视口）也因此得以保留。
 */
@SuppressLint("SetJavaScriptEnabled")
class StockKlineWebView(context: Context) : KRView(context) {
    private var disposed = false
    private var payload = "{}"
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
            if (next != payload) { payload = next; render() }
            true
        }
        "chartSelection" -> {
            @Suppress("UNCHECKED_CAST")
            callback = propValue as? ((Any?) -> Unit)
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
        // Kuikly 切换图型时复用同一实例（只移出/移回视图树，不重跑 init），
        // 重新入树时需把共享 WebView 重新挂载，否则整块白屏。
        if (web.parent !== this) {
            (web.parent as? ViewGroup)?.removeView(web)
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
    }

    override fun onDestroy() {
        disposed = true; callback = null
        // 只从本视图摘下；若共享 WebView 已被新实例接管（Kuikly 可能先建新后删旧），不能动它。
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
            // Inline the pinned bundle: no file-origin permission or runtime CDN access is needed.
            val script = context.assets.open("chart/klinecharts-9.8.12.min.js").bufferedReader().use { it.readText() }
            val html = context.assets.open("chart/index.html").bufferedReader().use { it.readText() }
            web.loadDataWithBaseURL("https://chart.local/", html.replace("/* ENGINE_BUNDLE */", script), "text/html", "UTF-8", null)
            shared = web
            return web
        }

        /** Activity 销毁时回收共享 WebView；downX/downY 只被触摸监听使用，随实例一起释放。 */
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
