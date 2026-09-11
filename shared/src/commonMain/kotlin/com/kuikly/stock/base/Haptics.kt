// 触觉反馈：把「轻 / 中 / 重」三档抽象成跨平台调用，Android 走 Vibrator、iOS 走 UIImpactFeedbackGenerator。
//
// 为什么要自己搭桥：Kuikly core 2.7.0 的 17 个内置 Module 里没有任何震动 API
// （commonMain + iosMain 全库搜 vibrat/haptic/impactFeedback 均为 0）。
// 所以复用宿主已有的 HRBridgeModule 扩一个方法，而不是新建 Module——
// 新建 Module 需要在 iOS 的 xcodeproj 里登记文件，而扩方法不需要动工程文件。
//
// 三端行为：
//   Android —— KRBridgeModule.vibrate()，用 VibrationEffect 一次性震动（权限已在 Manifest 声明）
//   iOS     —— HRBridgeModule.m 的 -vibrate:，用 UIImpactFeedbackGenerator
//   JS      —— 无原生实现，callNativeMethod 走到空实现；这里再包一层 runCatching 兜底
//
// 另一条纪律：震动是「锦上添花」，任何失败都不能影响主流程，所以调用点一律 fire-and-forget。
// 前提是模块本身已被注册（见 BasePager.createExternalModules）——否则这个「兜底」
// 其实是假的，原因见下面 hapticTick 的注释。

package com.kuikly.stock.base

internal enum class HapticStyle(val raw: String) {
    /** 轻：列表项点击、开关切换这类高频轻操作。 */
    Light("light"),

    /** 中：K 线锁定、下拉刷新触发——需要"咔哒"一下确认的落点。 */
    Medium("medium"),

    /** 重：长按进入框选这类模式切换，提示"状态变了"。 */
    Heavy("heavy"),
}

/**
 * 触发一次触觉反馈。平台无实现 / 页面已销毁 / 模块未注册等情况一律静默忽略。
 *
 * 注意不要改用 `Utils.currentBridgeModule()`：它走 `acquireModule`，模块缺失时
 * 会经 `throwRuntimeError` 抛异常——而该函数除了立即抛出还会排一个
 * `setTimeout(1) { throw ... }`，`runCatching` 只能接住立即那次，
 * 延迟那次会在 1ms 后崩掉整个进程。触觉是锦上添花，不能有这种风险。
 */
internal fun hapticTick(style: HapticStyle = HapticStyle.Light) {
    val module = Utils.currentBridgeModuleOrNull() ?: return
    runCatching { module.vibrate(style.raw) }
}
