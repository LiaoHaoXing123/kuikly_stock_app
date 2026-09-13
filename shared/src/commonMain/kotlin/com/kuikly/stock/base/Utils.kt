package com.kuikly.stock.base

import com.tencent.kuikly.core.base.BaseObject
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.PagerManager

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

    fun currentBridgeModuleOrNull(): BridgeModule? {
        val pager = runCatching { PagerManager.getPager(BridgeManager.currentPageId) }.getOrNull()
            ?: return null
        return runCatching { pager.getModule<BridgeModule>(BridgeModule.MODULE_NAME) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun logToNative(content: String) {
        bridgeModule(BridgeManager.currentPageId).log(content)
    }

    fun convertToPriceStr(price: Long): String {
        return (price / 100f).toString()
    }

}
