package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppMotion
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.View
import com.kuikly.stock.ui.theme.AppColor

internal enum class DrawerSide { LEFT, RIGHT }

private const val SCRIM_COLOR: Long = 0x66000000

private const val DEFAULT_DRAWER_WIDTH_RATIO = 0.82f

private val DRAWER_ENTER_ANIMATION: Animation = Animation.easeOut(AppMotion.DRAWER_IN_MS / 1000f)

private val DRAWER_EXIT_ANIMATION: Animation = Animation.easeIn(AppMotion.DRAWER_OUT_MS / 1000f)

private val SCRIM_ENTER_ANIMATION: Animation = Animation.easeOut(AppMotion.SCRIM_MS / 1000f)

private val SCRIM_EXIT_ANIMATION: Animation = Animation.easeIn(AppMotion.SCRIM_MS / 1000f)

internal fun drawerState(pager: Pager): Overlay =
    Overlay(pager, AppMotion.DRAWER_OUT_MS, onBack = null).apply {
        backCallback = object : BackPressCallback() {
            override fun handleOnBackPressed() {
                hide()
            }
        }
    }

internal fun Attr.scrimEnterExit(drawer: Overlay) {
    val entering = drawer.entering
    animate(SCRIM_ENTER_ANIMATION, entering)
    val leaving = drawer.leaving
    animate(SCRIM_EXIT_ANIMATION, leaving)
    opacity(if (entering || leaving) 0f else 1f)
}

internal fun Attr.drawerEnterExit(drawer: Overlay, side: DrawerSide) {
    val entering = drawer.entering
    animate(DRAWER_ENTER_ANIMATION, entering)
    val leaving = drawer.leaving
    animate(DRAWER_EXIT_ANIMATION, leaving)
    val offScreen = when (side) {
        DrawerSide.LEFT -> -100f
        DrawerSide.RIGHT -> 100f
    }
    transform(translate = Translate(percentageX = if (entering || leaving) offScreen else 0f))
}

internal fun ViewContainer<*, *>.sideDrawer(
    drawer: Overlay,
    pageWidth: Float,
    side: DrawerSide = DrawerSide.LEFT,
    widthRatio: Float = DEFAULT_DRAWER_WIDTH_RATIO,
    scrimColor: Long = SCRIM_COLOR,
    onScrimTap: () -> Unit,
    content: ViewContainer<*, *>.() -> Unit,
) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(scrimColor)
            scrimEnterExit(drawer)
        }

        event { click { onScrimTap() } }

        View {
            attr {
                drawerEnterExit(drawer, side)
                when (side) {
                    DrawerSide.LEFT -> absolutePosition(top = 0f, left = 0f, bottom = 0f)
                    DrawerSide.RIGHT -> absolutePosition(top = 0f, bottom = 0f, right = 0f)
                }
                width(pageWidth * widthRatio)
                backgroundColor(AppColor.SURFACE)
                flexDirectionColumn()
            }

            content()
        }
    }
}
