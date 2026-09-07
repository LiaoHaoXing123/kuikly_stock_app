// 通用工具集合。

package com.kuikly.stock.base

import com.tencent.kuikly.core.base.BaseObject
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.PagerManager

internal fun normalizeBreaks(s: String): String {
    if (s.isEmpty()) return s
    var t = s.replace("<br/>", "\n").replace("<br>", "\n")
    t = t.replace("\r\n", "\n")
    t = t.replace('\r', '\n')
    return t.replace("\\n", "\n")
}

internal fun splitBreaks(s: String): List<String> {
    return normalizeBreaks(s).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}

internal object Utils : BaseObject() {

    fun bridgeModule(pager: String): BridgeModule {
        return PagerManager.getPager(pager).acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
    }

    fun logToNative(pagerId: String, content: String) {
        bridgeModule(pagerId).log(content)
    }

    @Suppress("DEPRECATION")
    fun currentBridgeModule(): BridgeModule {
        return PagerManager.getPager(BridgeManager.currentPageId).acquireModule<BridgeModule>(
            BridgeModule.MODULE_NAME
        )
    }

    @Suppress("DEPRECATION")
    fun logToNative(content: String) {
        bridgeModule(BridgeManager.currentPageId).log(content)
    }

    fun convertToPriceStr(price: Long): String {
        return (price / 100f).toString()
    }

}
