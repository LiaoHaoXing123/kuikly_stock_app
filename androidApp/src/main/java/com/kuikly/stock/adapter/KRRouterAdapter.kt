// 路由适配器，处理 Kuikly 页面之间的跳转请求。
//
// 转场动画不在这里套：startActivity 之后立刻 override 只能覆盖「打开」，
// 系统返回键走的是 Activity.finish，对不上。打开/关闭统一由
// KuiklyRenderActivity + NavTransition 处理（同级 fade / 下钻从右滑入）。

package com.kuikly.stock.adapter

import android.app.Activity
import android.content.Context
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import com.kuikly.stock.KuiklyRenderActivity
import org.json.JSONObject

object KRRouterAdapter : IKRRouterAdapter {

    override fun openPage(
        context: Context,
        pageName: String,
        pageData: JSONObject,
    ) {
        KuiklyRenderActivity.start(context, pageName, pageData)
    }

    override fun closePage(context: Context) {
        (context as? Activity)?.finish()
    }
}
