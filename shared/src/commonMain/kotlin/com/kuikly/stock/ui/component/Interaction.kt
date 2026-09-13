package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppMotion
import com.kuikly.stock.ui.theme.ThemeManager
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.internal.GroupEvent

internal val PRESS_BG_LIGHT: Long get() = AppColor.PRESS_BG

internal const val PRESS_BG_DARK: Long = 0x33FFFFFF

internal val SKELETON_BG: Long get() = AppColor.SKELETON_BG

internal val SKELETON_BG_STRONG: Long get() = AppColor.SKELETON_BG_STRONG

internal const val SKELETON_BG_ON_DARK: Long = 0x59FFFFFF

internal const val PRESS_BG_NONE: Long = 0x00FFFFFF

internal const val PRESS_SCALE = AppMotion.PRESS_SCALE

private const val SHIMMER_BAND_WIDTH = 90f

private const val SHIMMER_START = -1.2f

private const val SHIMMER_END = 6f

private val SHIMMER_ANIMATION: Animation = Animation.linear(1.35f).repeatForever(true)

internal class MountPulse(scope: PagerScope) {

    private var tick by scope.observable(0)

    internal val generation: Int get() = tick

    internal fun bump() {
        tick++
    }

    internal fun settle() {
        if (tick != 0) tick = 0
    }
}

internal class PressState(scope: PagerScope) {

    private var key: String by scope.observable("")

    internal fun isPressed(tag: String): Boolean = key == tag

    internal fun press(tag: String) {
        if (key != tag) key = tag
    }

    internal fun release(tag: String) {

        if (key == tag) key = ""
    }

    internal fun releaseAll() {
        if (key.isNotEmpty()) key = ""
    }
}

internal fun Attr.pressedBg(
    press: PressState,
    tag: String,
    normal: Long,
    pressed: Long = PRESS_BG_LIGHT,
) {
    backgroundColor(if (press.isPressed(tag)) pressed else normal)
}

internal fun Attr.pressedScale(
    press: PressState,
    tag: String,
    normal: Float = 1f,
    pressed: Float = PRESS_SCALE,
) {
    val s = if (press.isPressed(tag)) pressed else normal
    transform(scale = Scale(s, s))
}

internal fun GroupEvent.pressFeedback(press: PressState, tag: String) {
    touchDown { press.press(tag) }
    touchUp { press.release(tag) }
    touchCancel { press.release(tag) }
}

internal fun ViewContainer<*, *>.skeletonBlock(
    height: Float,
    w: Float? = null,
    radius: Float = 4f,
    color: Long = SKELETON_BG,
    sweep: MountPulse? = null,
) {
    View {
        attr {
            if (w != null) {
                width(w)
            } else {
                flex(1f)
            }
            this.height(height)
            borderRadius(radius)
            backgroundColor(color)
        }
        if (sweep != null) {
            skeletonShimmerBand(sweep, radius)
        }
    }
}

internal const val MIN_SKELETON_SHOW_MS = 400L

private fun ViewContainer<*, *>.skeletonShimmerBand(sweep: MountPulse, radius: Float) {
    View {
        attr {
            val generation = sweep.generation
            animate(SHIMMER_ANIMATION, generation)
            absolutePosition(top = 0f, left = 0f, bottom = 0f)
            width(SHIMMER_BAND_WIDTH)
            borderRadius(radius)
            backgroundLinearGradient(
                Direction.TO_RIGHT,

                ColorStop(Color(if (ThemeManager.isDark) 0x00FFFFFFL else 0x00000000L), 0f),
                ColorStop(Color(if (ThemeManager.isDark) 0x66FFFFFFL else 0x33000000L), 0.5f),
                ColorStop(Color(if (ThemeManager.isDark) 0x00FFFFFFL else 0x00000000L), 1f),
            )
            val x = if (generation % 2 == 0) SHIMMER_START else SHIMMER_END
            transform(translate = Translate(percentageX = x))
        }
    }
}
