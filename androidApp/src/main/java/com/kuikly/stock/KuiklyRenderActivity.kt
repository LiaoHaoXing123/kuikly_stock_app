// 页面容器 Activity，承载 Kuikly 渲染视图，并把生命周期事件转发给渲染引擎。

package com.kuikly.stock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.kuikly.stock.adapter.KRColorParserAdapter
import com.kuikly.stock.adapter.KRFontAdapter
import com.kuikly.stock.adapter.KRImageAdapter
import com.kuikly.stock.adapter.KRLogAdapter
import com.kuikly.stock.adapter.KRRouterAdapter
import com.kuikly.stock.adapter.KRThreadAdapter
import com.kuikly.stock.adapter.KRUncaughtExceptionHandlerAdapter
import com.kuikly.stock.module.KRBridgeModule
import com.kuikly.stock.update.DataUpdateWorker
import com.kuikly.stock.module.KRShareModule
import org.json.JSONObject

class KuiklyRenderActivity : AppCompatActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private lateinit var hrContainerView: ViewGroup
    private lateinit var loadingView: View
    private lateinit var errorView: View

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)

    private val pageName: String
    get() {
        val pn = intent.getStringExtra(KEY_PAGE_NAME) ?: ""
        return if (pn.isNotEmpty()) {
            return pn
            } else {
            "home_dashboard"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val motion = navMotion()
        // API 34+ 必须在 onCreate 登记，才会作用于「这一次」打开和之后的关闭。
        NavTransition.registerForApi34(this, motion)

        setContentView(R.layout.activity_hr)
        setupImmersiveMode()
        requestNotificationPermission()
        hrContainerView = findViewById(R.id.hr_container)
        loadingView = findViewById(R.id.hr_loading)
        errorView = findViewById(R.id.hr_error)
        // 同级 Tab 切换的入场：窗口底色先把旧页盖住，内容再延迟淡入（见 NavTransition）。
        NavTransition.applyTabContentFadeIn(motion, hrContainerView)
        // FADE 空窗底色对齐页面背景，避免浅色下闪白/深色下闪黑（见 NavTransition）。
        if (motion == NavMotion.FADE) {
            NavTransition.applyTabWindowBackground(this, NavTransition.currentDark)
        }
        kuiklyRenderViewDelegator.onAttach(hrContainerView, "", pageName, createPageData())
    }

    override fun finish() {
        super.finish()
        // API 33-：系统返回键和 Router.closePage 都走 finish，转场不会漏。
        NavTransition.pendingClose(this, navMotion())
    }

    override fun onDestroy() {
        super.onDestroy()
        kuiklyRenderViewDelegator.onDetach()
    }

    private fun navMotion(): NavMotion = NavTransition.motionOfJson(intent.getStringExtra(KEY_PAGE_DATA))

    override fun onPause() {
        super.onPause()
        kuiklyRenderViewDelegator.onPause()
    }

    override fun onResume() {
        super.onResume()
        kuiklyRenderViewDelegator.onResume()
        try { DataUpdateWorker.schedule(this) } catch (_: Throwable) { }
    }

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        with(kuiklyRenderExport) {
            moduleExport(KRBridgeModule.MODULE_NAME) {
                KRBridgeModule()
            }
            moduleExport(KRShareModule.MODULE_NAME) {
                KRShareModule()
            }
        }
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalRenderView(kuiklyRenderExport)
        with(kuiklyRenderExport) {
            renderViewExport("StockChartGestureView", { context -> StockChartGestureView(context) })
            renderViewExport("StockKlineWebView", { context -> StockKlineWebView(context) })
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun createPageData(): Map<String, Any> {
        val param = argsToMap()
        param["appId"] = 1
        param["nativeChartGestures"] = true
        param["matureChart"] = true
        return param
    }

    private fun argsToMap(): MutableMap<String, Any> {
        val jsonStr = intent.getStringExtra(KEY_PAGE_DATA) ?: return mutableMapOf()
        return JSONObject(jsonStr).toMap()
    }

    @Suppress("DEPRECATION")
    fun updateSystemBars(dark: Boolean) {
        NavTransition.currentDark = dark
        val flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        // Detail headers retain a blue surface in both themes.
        val blueHeader = pageName in setOf("stock_detail", "index_detail")
        window.decorView.systemUiVisibility = flags or
            (if (dark || blueHeader) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) or
            (if (!dark && Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        window.navigationBarColor = if (dark) Color.rgb(28, 33, 40) else Color.WHITE
    }

    @Suppress("DEPRECATION")
    private fun setupImmersiveMode() {        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window?.statusBarColor = Color.TRANSPARENT
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

    }

    companion object {

        private const val KEY_PAGE_NAME = "pageName"
        private const val KEY_PAGE_DATA = "pageData"

        init {
            initKuiklyAdapter()
        }

        fun start(context: Context, pageName: String, pageData: JSONObject) {
            val starter = Intent(context, KuiklyRenderActivity::class.java)
            starter.putExtra(KEY_PAGE_NAME, pageName)
            starter.putExtra(KEY_PAGE_DATA, pageData.toString())
            context.startActivity(starter)
            // API 33- 必须紧挨 startActivity；API 34+ 由目标 Activity.onCreate 登记。
            NavTransition.pendingOpen(context as? Activity, NavTransition.motionOf(pageData))
        }

        private fun initKuiklyAdapter() {
            with(KuiklyRenderAdapterManager) {
                krImageAdapter = KRImageAdapter(KRApplication.application)
                krLogAdapter = KRLogAdapter
                krUncaughtExceptionHandlerAdapter = KRUncaughtExceptionHandlerAdapter
                krFontAdapter = KRFontAdapter
                krColorParseAdapter = KRColorParserAdapter(KRApplication.application)
                krRouterAdapter = KRRouterAdapter
                krThreadAdapter = KRThreadAdapter()
            }
        }
    }
}
