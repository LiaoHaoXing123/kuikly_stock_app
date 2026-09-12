// 主题运行时：当前模式、怎么切、以及「切完之后页面凭什么会重画」的那根引线。
//
// 【为什么单独一层】
// `AppTheme.kt` 只回答「现在该用哪个颜色」；「谁记住当前是什么模式」和「切完页面怎么
// 知道要重画」是另外两件事。分开写，token 文件就不会被运行时逻辑撑大。
//
// 【切主题为什么能生效 —— 都是框架事实，不是想当然，逐条列来源】
// 1) Kuikly 的响应式观察者是 **per-pager** 的：`PagerManager.reactiveObserverMap[pagerId]`，
//    取用走 `getCurrentReactiveObserver()`（按 `BridgeManager.currentPageId` 查，查不到直接抛）。
//    框架里**没有全局 observable** —— `observable()` 是 `PagerScope` 的扩展（见
//    `reactive/handler/ReactivePropertyHandler.kt`）。所以「主题世代号」只能挂在页面上。
// 2) `DeclarativeBaseView.attr()` 内部用 `ReactiveObserver.bindValueChange` 把整个 attr 块
//    包了起来；attr 块里读到的 observable 一变，整块重跑并重新下发 native prop。
// 3) 触发时 `fireObserverFn` 会**重新收集依赖**（`startCollectDependency()` + 再跑一遍
//    observerFn + `addObserver`），所以「读一次 ⇒ 订阅」这件事可以反复发生，
//    不是只生效一次。`notifyGetValue` 也不排除「正在变更的那个属性」自己被读，
//    这点很关键：否则第一次切换之后 attr 块就不再订阅主题了。
//
// 于是只要 `AppColor.X` 的取值过程「顺手读一次当前页面的 themeGeneration」，
// 每个用到 token 的 attr 块就自动订阅了主题 —— **804 处调用点一行都不用改。**
//
// 【为什么除世代号外还需要 epoch】
// Android 是「一页一个 Activity」（`KRRouterAdapter.openPage` → `KuiklyRenderActivity.start`）。
// 从「我的」切回「首页」是回到**已经存在**的那个 Activity，视图树不会重建，
// 旧颜色会原样留在屏幕上。所以每个页面在 `pageDidAppear` 里比对一次 `epoch`，
// 发现落后就自增自己的 themeGeneration 重画 —— 落在 `BasePager` 上，10 个页面白拿。
//
// 【框架已有的钩子，以及为什么没用它】
// 框架预留了 `Pager.PAGER_EVENT_THEME_DID_CHANGED`（常量 "themeDidChanged"）→
// `Pager.themeDidChanged(data)`，语义是「宿主告诉我系统主题变了」。但：
//   - 当前三个宿主（androidApp / iosApp / ohosApp）**没有任何一处会发这个事件**；
//   - 它也只是把消息递到页面，不负责重画。
// 所以本轮只做**应用内切换**，不跟随系统。将来要做「跟随系统」，
// 在宿主里发这个事件、转成 `ThemeManager.set(...)` 即可，页面侧不用再动。

package com.kuikly.stock.ui.theme

/**
 * 主题模式。
 *
 * [key] 同时是持久化用的值，**不要随手改**：改了等于让老用户已保存的偏好失效。
 */
enum class ThemeMode(val key: String) {
    LIGHT("light"),
    DARK("dark");

    companion object {
        /** 未知/为空一律回落浅色：升级后没存过偏好的用户，观感保持不变。 */
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

/**
 * 由**页面**实现，让 [AppColor] 取值时能把「主题世代」登记成依赖。
 *
 * 只有 `BasePager` 需要实现，业务页面不必关心这里。
 */
interface ThemeHost {
    /**
     * 主题世代号。切主题后由 `BasePager.refreshTheme()` 自增。
     * 读它本身就是「订阅主题」这个动作。
     */
    val themeGeneration: Int
}

/** 全局主题状态。进程内唯一一份。 */
object ThemeManager {

    /**
     * 当前模式。
     *
     * 故意是普通 `var` 而不是 observable —— 见文件头第 (1) 条：观察者是 per-pager 的，
     * 全局 observable 做不到「一次变更通知所有页面」。那件事由 [epoch] 加上各页面自己的
     * `themeGeneration` 共同完成。
     */
    var mode: ThemeMode = ThemeMode.LIGHT
        private set

    /**
     * 主题世代。每次 [set] 自增。
     *
     * 页面记下「自己渲染时看到的世代」，在 `pageDidAppear` 里比对：
     * 落后说明这期间主题变过，需要重画（见 `BasePager.pageDidAppear`）。
     */
    var epoch: Int = 0
        private set

    val isDark: Boolean get() = mode == ThemeMode.DARK

    /** 设置模式。同值直接返回，避免一次无意义的全页重画。 */
    fun set(newMode: ThemeMode) {
        if (mode == newMode) return
        mode = newMode
        epoch++
    }

    /** 在明/暗之间切换，返回切换后的模式（调用方要持久化它）。 */
    fun toggle(): ThemeMode {
        set(if (isDark) ThemeMode.LIGHT else ThemeMode.DARK)
        return mode
    }

    /**
     * 启动时从偏好恢复。
     *
     * **故意不动 [epoch]**：此刻还没有任何页面渲染过，之后构造的页面本来就会读到
     * 恢复后的模式；若在这里自增，反而会让「构造时记下的世代」和「当前世代」错开，
     * 造成首屏多画一次。
     */
    fun restore(key: String?) {
        mode = ThemeMode.fromKey(key)
    }
}
