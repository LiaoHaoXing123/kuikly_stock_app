// 页面转场规格：同级淡入、下钻从右滑入、返回从右滑出。
//
// 同级模块（底部 Tab）之间是**三段式**「旧页让位 → 空窗 → 内容淡入」，共 330ms。
// 为什么不用窗口 alpha 做交叉淡出：中段两层都半透明，会同时看到两页内容（重影，看着像
// 错位）；而窗口 alpha 连窗口底色一起淡，旧页淡完后中段还会直接透出桌面。所以入场改成
// 「不透明的窗口底色瞬间盖住旧页 + 内容延迟 120ms 后 210ms 淡入」，见 applyTabContentFadeIn。
// 三段时长在 common 侧 AppMotion 定规格（TAB_OUT_MS 90 / TAB_GAP_MS 30 / TAB_IN_MS 210），
// 这里 120 = 90 + 30，改动要两边一起改。
//
// 为什么抽这一层：动画必须在 startActivity 之后、finish 之后立刻挂上，
// 还要照顾 API 34 的 overrideActivityTransition（要在目标 Activity.onCreate 里登记）。
// 调用点分散，规格集中在这里，三处不会各写一套资源 id。

package com.kuikly.stock

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.json.JSONObject

internal enum class NavMotion {
    /** 启动页，不要自定义转场（从桌面滑进来会很怪）。 */
    NONE,

    /** 同级模块（底部 Tab）。pageData.transition=fade。 */
    FADE,

    /** 层级下钻（详情页等）。iOS 风格 push/pop。 */
    SLIDE,
}

internal object NavTransition {

    /** 与 common 侧 AppShell.NAV_TRANSITION_KEY / NAV_TRANSITION_FADE 对齐。 */
    const val KEY = "transition"
    const val FADE = "fade"

    /**
     * 进程内最近一次系统栏同步时的主题（浅/深）。
     *
     * 由 [KuiklyRenderActivity.updateSystemBars] 每次主题切换时写入；Tab 切换开新页
     * 时新 Activity 的 onCreate 读它来决定 FADE 空窗底色，避免浅色下露纯白、深色下露纯黑。
     */
    @Volatile
    var currentDark: Boolean = false

    /**
     * FADE 空窗期的窗口底色：与页面背景色一致（common 侧 AppColor.SURFACE_SOFT）。
     *
     * Tab 切换三段式里有一段「只有底、无内容」的空窗（内容 alpha=0，见
     * [applyTabContentFadeIn]）；如果窗口底色是主题默认的纯白/纯黑，切换瞬间就会
     * 「白色/黑色闪过」。这里把底色调成页面背景色，空窗与页面视觉连续。
     */
    fun applyTabWindowBackground(activity: Activity, dark: Boolean) {
        val bg = if (dark) 0xFF22262D.toInt() else 0xFFF5F5F5.toInt()
        activity.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
    }

    /** 旧页让位到新页内容开始之间的空窗（ms）。= AppMotion 的出场段 90 + 间隙 30。 */
    private const val TAB_COVER_MS = 120L

    /** 新页内容淡入时长（ms）。= AppMotion.TAB_IN_MS。 */
    private const val TAB_FADE_IN_MS = 210L

    fun motionOf(pageData: JSONObject): NavMotion =
        if (pageData.optString(KEY) == FADE) NavMotion.FADE else NavMotion.SLIDE

    fun motionOfJson(json: String?): NavMotion {
        if (json.isNullOrEmpty()) return NavMotion.NONE
        return try {
            motionOf(JSONObject(json))
        } catch (_: Throwable) {
            NavMotion.SLIDE
        }
    }

    /**
     * 同级模块入场的**内容淡入**。三段式在 Android 上的落地方式。
     *
     * 为什么不用窗口 alpha 做淡入（`kr_module_fade_in` 那种做法）：窗口动画的 alpha 作用在
     * 整个窗口 surface 上，**连窗口底色一起淡**。于是入场中段「新页内容半透明 + 新页底色
     * 半透明 + 旧页已淡完」→ 直接透出桌面（深色壁纸下是一层发脏的灰）。实测逐帧均值从
     * 21 一路爬到 225，就是这个过程。
     *
     * 改成 Material fade through 的正解：让**不透明的窗口底色**先把旧页整个盖住，
     * 只有内容延迟 [TAB_COVER_MS] 后淡入 [TAB_FADE_IN_MS]。
     * 于是：① 旧页让位（被中性底接管）→ ② 空窗（只有底、无内容）→ ③ 内容淡入。
     * 任一时刻背后都是**本 App 的窗口底色**，不会透出桌面，也不会有两页重叠的重影。
     *
     * iOS / 鸿蒙不需要这一步：那边淡入的是页面内容，App 窗口底色天然不参与，本就不会透底。
     */
    fun applyTabContentFadeIn(motion: NavMotion, content: View) {
        if (motion != NavMotion.FADE) return
        content.alpha = 0f
        content.animate()
            .alpha(1f)
            .setStartDelay(TAB_COVER_MS)
            .setDuration(TAB_FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * API 34+：在**新打开的** Activity.onCreate 里同时登记 open / close。
     * 系统返回键也会走这里登记的 CLOSE，不必再靠 closePage 补一刀。
     */
    fun registerForApi34(activity: Activity, motion: NavMotion) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        when (motion) {
            NavMotion.NONE -> return
            NavMotion.FADE -> {
                // 入场：窗口级 0/0 —— 窗口以不透明底色瞬间盖住旧页，淡入交给内容层
                // （见 applyTabContentFadeIn）。给 enter 动画会让底色跟着淡，中段透出桌面。
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
                // 返回：关掉的是自己，下面那页是不透明的、且不动，所以淡出自己即可，
                // 不会出现「两层都半透明」的中间态。
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_CLOSE,
                    0,
                    R.anim.kr_module_fade_out,
                )
            }
            NavMotion.SLIDE -> {
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_OPEN,
                    R.anim.kr_slide_in_right,
                    R.anim.kr_slide_out_left,
                )
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_CLOSE,
                    R.anim.kr_slide_in_left,
                    R.anim.kr_slide_out_right,
                )
            }
        }
    }

    /** API 33 及以下：紧挨着 startActivity 调，作用在发起方 Activity。 */
    fun pendingOpen(activity: Activity?, motion: NavMotion) {
        if (activity == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val (enter, exit) = when (motion) {
            NavMotion.NONE -> return
            NavMotion.FADE -> 0 to 0
            NavMotion.SLIDE -> R.anim.kr_slide_in_right to R.anim.kr_slide_out_left
        }
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(enter, exit)
    }

    /** API 33 及以下：紧挨着 finish 调，作用在正在关闭的 Activity。 */
    fun pendingClose(activity: Activity, motion: NavMotion) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val (enter, exit) = when (motion) {
            NavMotion.NONE -> return
            NavMotion.FADE -> 0 to R.anim.kr_module_fade_out
            NavMotion.SLIDE -> R.anim.kr_slide_in_left to R.anim.kr_slide_out_right
        }
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(enter, exit)
    }
}
