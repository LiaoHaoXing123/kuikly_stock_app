// 延时执行回调，返回定时任务 id。

@file:Suppress("DEPRECATION")

package com.kuikly.stock.base

import com.tencent.kuikly.core.base.IPagerId
import com.tencent.kuikly.core.base.pagerId

internal val IPagerId.bridgeModule: BridgeModule by pagerId {
    Utils.bridgeModule(it)
}

internal fun IPagerId.setTimeout(delay: Int, callback: () -> Unit): String {
    return com.tencent.kuikly.core.timer.setTimeout(pagerId, delay, callback)
}
