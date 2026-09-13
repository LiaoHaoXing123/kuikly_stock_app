package com.kuikly.stock.ui.component

import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.kuikly.stock.ui.theme.AppMotion

internal open class Overlay(
    private val pager: Pager,
    private val exitMs: Int = OVERLAY_EXIT_MS,
    onBack: (() -> Unit)? = null,
) {

    private var mounted by pager.observable(false)

    private var tick by pager.observable(0)

    private var away by pager.observable(false)

    private var generation = 0

    internal var backCallback: BackPressCallback? = null

    init {
        if (onBack != null) {
            backCallback = object : BackPressCallback() {
                override fun handleOnBackPressed() {
                    onBack()
                }
            }
        }
    }

    internal val isVisible: Boolean get() = mounted

    internal val entering: Boolean get() = tick == 0

    internal val leaving: Boolean get() = away

    internal fun show() {

        generation++
        away = false
        mounted = true
        tick++
        registerBack()
    }

    internal fun hide() {
        if (!mounted) return
        generation++
        val mine = generation
        away = true
        pager.lifecycleScope.launch {
            delay(exitMs)
            if (mine != generation) return@launch
            mounted = false
            away = false

            if (tick != 0) tick = 0
            unregisterBack()
        }
    }

    internal fun toggle() {
        if (mounted) hide() else show()
    }

    private fun registerBack() {
        val cb = backCallback ?: return
        val handler = pager.getBackPressHandler()
        if (!handler.containsCallback(cb)) handler.addCallback(cb)
    }

    private fun unregisterBack() {
        val cb = backCallback ?: return
        pager.getBackPressHandler().removeCallback(cb)
    }
}

private val OVERLAY_ENTER_ANIMATION: Animation = Animation.easeOut(AppMotion.OVERLAY_IN_MS / 1000f)

private val OVERLAY_EXIT_ANIMATION: Animation = Animation.easeIn(AppMotion.OVERLAY_OUT_MS / 1000f)

private const val OVERLAY_EXIT_MS = AppMotion.OVERLAY_OUT_MS

private const val OVERLAY_SCALE = 0.94f

internal fun Attr.overlayEnterExit(overlay: Overlay, scaleFrom: Float = OVERLAY_SCALE) {
    val entering = overlay.entering
    animate(OVERLAY_ENTER_ANIMATION, entering)
    val leaving = overlay.leaving
    animate(OVERLAY_EXIT_ANIMATION, leaving)

    val hidden = entering || leaving
    opacity(if (hidden) 0f else 1f)
    val s = if (hidden) scaleFrom else 1f
    transform(scale = Scale(s, s))
}
