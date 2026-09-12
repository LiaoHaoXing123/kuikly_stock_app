// 页面基类：统一注册扩展 Module（HRBridgeModule） + 承接全局主题的刷新。
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
// 超类，这大概就是它一直没被接上的原因。故改为 public。

package com.kuikly.stock.base

import com.kuikly.stock.ui.component.MountPulse
import com.kuikly.stock.ui.component.StepPulse
import com.kuikly.stock.ui.theme.ThemeHost
import com.kuikly.stock.ui.theme.ThemeManager
import com.kuikly.stock.ui.theme.ThemeMode
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable

/**
 * 所有页面的基类。
 *
 * 职责只有两件，都必须是「一处写、全页面白拿」的横切关注点：
 *   1. 注册 `HRBridgeModule`（见文件头那个真实的坑）；
 *   2. 主题：恢复用户选择、以及在「期间主题变过」时把本页重画一遍。
 *
 * 主题部分为什么落在这里，而不是让 10 个页面各写一遍：切主题这件事的触发点是
 * 「用户切回某个已经存在的 Activity」。Android 一页一个 Activity，返回是复用实例、
 * 视图树不重建，颜色会留在切换前的样子。只有 `pageDidAppear` 是每页必经的入口，
 * 而所有页面都已经在 `super.pageDidAppear()` 上串好了 —— 于是这里就是唯一正确的挂点。
 */
abstract class BasePager : Pager(), ThemeHost {

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
     * 刷新结束后箭头会一直转（见 Motion.kt 头部说明）。
     */
    internal val refreshSpin: StepPulse by lazy(LazyThreadSafetyMode.NONE) { StepPulse(this) }

    /**
     * 主题世代号（[ThemeHost] 的实现）。
     *
     * `AppColor` 每次取值都会读它一次，读这个动作就把「本页订阅了主题」登记进了
     * 那个 attr 块的依赖里；这里自增一下，本页所有用到颜色的 attr 块就会重跑
     * 并下发新颜色。机制来源见 `ui/theme/Theme.kt` 头部。
     *
     * 底层用私有委托字段而不是直接公开 observable：`ThemeHost` 只承诺「能读」，
     * 外部不该有本事把它改成任意值（那样会绕开 [renderedThemeEpoch] 的记账）。
     */
    // 显式写 `this.observable(...)`：包内同时存在一个已废弃的顶层 `observable(init)`，
    // 不加接收者会有歧义（PagerScope 那个才是我们要的）。
    private var themeGen: Int by this.observable(0)

    override val themeGeneration: Int get() = themeGen

    /** 本页**渲染时**看到的主题世代。与 [ThemeManager.epoch] 不一致 ⇒ 期间切过主题。 */
    private var renderedThemeEpoch: Int = ThemeManager.epoch

    // ⚠️ 恢复主题**必须排在 super 之前**，这里踩过一个真坑：
    // 框架的调用顺序是 `initModule() → didMoveToParentView() → didInit() → createBody()`，
    // 看上去 createBody 在 didInit 之后；但 `Pager.didInit()` 内部是
    // `super<ComposeView>.didInit()`，而 **`ComposeView.didInit()` 里直接调了 `body()()`**
    // ——也就是说整棵视图树是在 `super.didInit()` 里建起来的，
    // 排在它后面的代码，对首屏来说已经是「渲染完成之后」了。
    //
    // 真机表现（已用日志确认）：首屏 4 张卡片全部按浅色色板取色，之后才读到偏好里的
    // dark 并切到深色色板；于是只有「因别的原因重跑过」的那些 attr 块会补上新色，
    // 结果是**半个页面换肤**（首页上恰好只有读 watchRoll 的那张卡重跑，于是只有它变深）。
    override fun didInit() {
        ensureThemeRestored()
        super.didInit()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (renderedThemeEpoch != ThemeManager.epoch) {
            refreshTheme()
        }
    }

    /**
     * 让本页按当前主题重画。
     *
     * 页面重新出现时会自动调用（见 [pageDidAppear]）；**切换主题的那个页面自己要
     * 再手动调一次** —— 它就站在屏幕上，等不到下次 pageDidAppear。
     */
    fun refreshTheme() {
        renderedThemeEpoch = ThemeManager.epoch
        themeGen++
    }

    /**
     * 切换主题：改全局模式 → 落盘 → 本页重画。
     *
     * 落盘放在这里而不是调用页：**读（[ensureThemeRestored]）和写必须同一个键、
     * 同一处代码**，否则很容易改成「写 A 键、读 B 键」，表现是「重启后主题丢了」，
     * 而且完全不报错。
     *
     * 这里只重画**当前页**；其它已在后台的页面由它们自己的 [pageDidAppear] 追上来。
     */
    fun setTheme(mode: ThemeMode) {
        if (ThemeManager.mode == mode) return
        ThemeManager.set(mode)
        runCatching {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(THEME_MODE_KEY, mode.key)
        }
        refreshTheme()
    }

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        return externalModules
    }

    /**
     * 宿主主动告知「系统主题变了」时跟随。
     *
     * 事件名是框架预留的 `Pager.PAGER_EVENT_THEME_DID_CHANGED`，约定载荷 `{"mode":"dark"}`。
     * **当前三个宿主都没有发过这个事件**，所以本方法今天是死代码；留着是为了让
     * 「深色模式跟随系统」将来只需要改宿主（发事件），页面侧一行都不用再动。
     * 写在这里而不是每个页面各写一遍，理由同 [pageDidAppear]。
     */
    override fun themeDidChanged(data: JSONObject) {
        val mode = data.optString("mode")
        if (mode.isEmpty()) return
        ThemeManager.set(ThemeMode.fromKey(mode))
        refreshTheme()
    }

    /**
     * 启动后第一次创建页面时，把上次的主题选择从偏好里读回来。
     *
     * 由 [didInit] 在 `super` **之前**调用：模块早在 `initModule()` 就注册好了，
     * 而视图树要等 `super.didInit()` 才建（见 [didInit] 处的说明）。
     * 排在这里，首屏第一次取色拿到的就是恢复后的色板，不会先渲染一遍浅色。
     *
     * 只读一次（[themeRestored]）：后面的页面不必再查，反正 [ThemeManager] 是全局的。
     */
    private fun ensureThemeRestored() {
        if (themeRestored) return
        themeRestored = true
        // 读不到就按浅色（也是 fromKey 的默认），不能因为偏好异常把首屏搞崩。
        val saved = runCatching {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .getItem(THEME_MODE_KEY)
        }.getOrNull()
        ThemeManager.restore(saved)
    }

    private companion object {
        /** 主题偏好的存储键。 */
        const val THEME_MODE_KEY = "app_theme_mode"

        /** 进程内只从偏好恢复一次。 */
        var themeRestored = false
    }
}
