// 路由适配器，处理 Kuikly 页面之间的跳转请求。

package com.kuikly.stock.adapter

import android.app.Activity
import android.content.Context
import android.os.Build
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import com.kuikly.stock.KuiklyRenderActivity
import com.kuikly.stock.R
import org.json.JSONObject

object KRRouterAdapter : IKRRouterAdapter {

    /** 与 common 侧 AppShell 的 NAV_TRANSITION_KEY / NAV_TRANSITION_FADE 对齐。 */
    private const val KEY_TRANSITION = "transition"
    private const val TRANSITION_FADE = "fade"

    override fun openPage(
        context: Context,
        pageName: String,
        pageData: JSONObject,
    ) {
        KuiklyRenderActivity.start(context, pageName, pageData)
        // 页面已经按系统默认转场（横切）启动了，同级目的地再换成淡入
        if (pageData.optString(KEY_TRANSITION) == TRANSITION_FADE) applyFadeTransition(context)
    }

    override fun closePage(context: Context) {
        (context as? Activity)?.finish()
    }

    /**
     * 同级模块切换（底部 Tab 之间）用「新页面淡入」代替系统横切。
     *
     * 为什么出场动画留空：新页面在上层、旧页面在下层，旧页面保持不透明就是一块天然的背景；
     * 两边同时淡出会在中段透出桌面（Material 的 fade through 里出场只占前 30%，正是为此，
     * 但窗口级动画拿不到那段编排）。所以这里只让新页面在旧页面上淡入，观感与 fade through
     * 一致又不会发灰。
     *
     * 为什么不加 fade through 里 0.92→1.0 的缩放：那个缩放作用在「容器内的内容」上，
     * 容器自己不缩放；窗口级缩放会让新页面四周露出旧页面，看起来像页面在弹跳。
     */
    private fun applyFadeTransition(context: Context) {
        val activity = context as? Activity ?: return
        val enter = R.anim.kr_module_fade_in
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enter, 0)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enter, 0)
        }
    }
}
