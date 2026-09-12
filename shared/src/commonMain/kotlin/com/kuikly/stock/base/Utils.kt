// 桥接工具集合。
//
// 纯字符串工具（normalizeBreaks / splitBreaks）已迁到 `com.kuikly.stock.util.TextUtil`。
// 这里只留「需要拿到 BridgeModule 才能做的事」——它们和桥的生命周期绑在一起，
// 放到 util 里会让 util 反向依赖 base。

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

    /**
     * 与 [currentBridgeModule] 等价，但**模块缺失时返回 null 而不是抛异常**。
     *
     * 为什么需要它：`acquireModule` 在模块未注册时走 `throwRuntimeError`，而那个函数
     * 除了立即抛出，还会额外排一个 `setTimeout(1) { throw ... }`——调用方即使包了
     * `runCatching` 也挡不住，1ms 后定时器线程上的那次抛出会直接崩掉进程。
     * 所以「锦上添花」型的调用（触觉反馈之类）一律走这条不抛异常的路。
     */
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
