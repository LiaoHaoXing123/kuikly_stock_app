// 页面转场规格：同级淡入、下钻从右滑入、返回从右滑出。
//
// 为什么抽这一层：动画必须在 startActivity 之后、finish 之后立刻挂上，
// 还要照顾 API 34 的 overrideActivityTransition（要在目标 Activity.onCreate 里登记）。
// 调用点分散，规格集中在这里，三处不会各写一套资源 id。

package com.kuikly.stock

import android.app.Activity
import android.os.Build
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
     * API 34+：在**新打开的** Activity.onCreate 里同时登记 open / close。
     * 系统返回键也会走这里登记的 CLOSE，不必再靠 closePage 补一刀。
     */
    fun registerForApi34(activity: Activity, motion: NavMotion) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        when (motion) {
            NavMotion.NONE -> return
            NavMotion.FADE -> {
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_OPEN,
                    R.anim.kr_module_fade_in,
                    0,
                )
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
            NavMotion.FADE -> R.anim.kr_module_fade_in to 0
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
