package com.kuikly.stock

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.json.JSONObject

internal enum class NavMotion {

    NONE,

    FADE,

    SLIDE,
}

internal object NavTransition {

    const val KEY = "transition"
    const val FADE = "fade"

    @Volatile
    var currentDark: Boolean = false

    fun applyTabWindowBackground(activity: Activity, dark: Boolean) {
        val bg = if (dark) 0xFF22262D.toInt() else 0xFFF5F5F5.toInt()
        activity.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
    }

    private const val TAB_COVER_MS = 120L

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

    fun registerForApi34(activity: Activity, motion: NavMotion) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        when (motion) {
            NavMotion.NONE -> return
            NavMotion.FADE -> {

                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)

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
