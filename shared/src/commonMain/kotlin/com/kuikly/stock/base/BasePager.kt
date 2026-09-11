// 页面基类：统一注册扩展 Module（HRBridgeModule）。
//
// 【为什么必须有这一层 —— 这里踩过一个真实的坑】
// Kuikly 的 `Pager.acquireModule` 在模块未注册时会调用 `throwRuntimeError`，
// 而它的实现是：
//     fun throwRuntimeError(message: String) {
//         setTimeout(1) { throw RuntimeException(message) }   // ← 延迟抛
//         throw RuntimeException(message)                     // ← 立即抛
//     }
// 也就是说调用方**即使包了 runCatching 也挡不住**：立即抛出那次被 runCatching
// 吃掉了，但排进 setTimeout 的那次会在 1ms 后于定时器线程上把整个进程打崩
// （FATAL EXCEPTION: HRContextQueueHandlerThread）。
//
// 所以「用到扩展 Module 的页面」必须真的把模块注册上，不能指望调用点兜底。
// 而这个项目之前 10 个页面全部直接继承 `Pager`，`createExternalModules` 从未
// 被重写过 —— 模块从来没注册过：触觉反馈一次都没生效，而且每次点击都会崩。
//
// 现在所有页面统一继承本类，保证「页面上都有 HRBridgeModule」是全局不变量。
//
// 注：本类原先是 `internal`，而页面都是 public，public 类无法继承 internal
// 超类，这大概就是它一直没被接上的原因。故改为 public，且只保留模块注册这一件事，
// 不额外引入夜间模式等行为变化（保证接入本类不改变任何现有表现）。

package com.kuikly.stock.base

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.pager.Pager

abstract class BasePager : Pager() {

    /**
     * 骨架屏扫光驱动。全局一份：同一页面上可能有多个骨架区（整页首屏、K线、分时、盘口），
     * 它们共用同一个世代计数即可，不必各配一份。
     *
     * 用法：把 loading 标志翻成 true 之后立刻 `skeletonPulse.bump()`——
     * 此刻骨架屏刚挂载，自增才会真的驱动位移动画（原因见 Interaction.kt 头部）。
     * 首屏那次 loading 发生在 didInit（body 尚未构建），所以页面还应在
     * pageDidAppear 里补一次 bump()。
     */
    internal val skeletonPulse: MountPulse by lazy(LazyThreadSafetyMode.NONE) { MountPulse(this) }

    // 浮层入场不在这里：每个浮层一个 Overlay（自带显隐 + 驱动值），
    // 共用一份驱动值会在「A 开着时关掉 B」的瞬间把 A 也拉回起始态。

    /**
     * AI 分析中三点波浪的步进驱动。个股页与指数页共用同一套相位表（[aiDotWaveDots]）。
     *
     * 用法：`aiDotWave.loop(AI_DOT_STEP_MS) { isAnalyzing }`，分析结束自然退出。
     */
    internal val aiDotWave: StepPulse by lazy(LazyThreadSafetyMode.NONE) { StepPulse(this) }

    /**
     * 下拉刷新旋转箭头的步进驱动。
     *
     * 用循环步进而不是 `repeatForever`：后者挂上就停不下来，
     * 刷新结束后箭头会一直转（见 Anim.kt 头部说明）。
     */
    internal val refreshSpin: StepPulse by lazy(LazyThreadSafetyMode.NONE) { StepPulse(this) }

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        return externalModules
    }
}
