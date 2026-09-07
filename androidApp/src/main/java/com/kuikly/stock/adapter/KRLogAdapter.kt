// 日志适配器，把 Kuikly 框架的日志输出到 Android 的 Log 系统。

package com.kuikly.stock.adapter

import android.util.Log
import com.tencent.kuikly.core.render.android.adapter.IKRLogAdapter

object KRLogAdapter : IKRLogAdapter {

    override val asyncLogEnable: Boolean
    get() = true

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }
}
