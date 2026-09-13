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

abstract class BasePager : Pager(), ThemeHost {

    internal val skeletonPulse: MountPulse by lazy(LazyThreadSafetyMode.NONE) { MountPulse(this) }

    internal val aiDotWave: StepPulse by lazy(LazyThreadSafetyMode.NONE) { StepPulse(this) }

    internal val refreshSpin: StepPulse by lazy(LazyThreadSafetyMode.NONE) { StepPulse(this) }

    private var themeGen: Int by this.observable(0)

    override val themeGeneration: Int get() = themeGen

    private var renderedThemeEpoch: Int = ThemeManager.epoch

    override fun didInit() {
        ensureThemeRestored()
        super.didInit()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        syncSystemBars()
        if (renderedThemeEpoch != ThemeManager.epoch) {
            refreshTheme()
        }
    }

    fun refreshTheme() {
        renderedThemeEpoch = ThemeManager.epoch
        themeGen++
        syncSystemBars()
    }

    private fun syncSystemBars() {
        acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).updateSystemBars(ThemeManager.isDark)
    }

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

    override fun themeDidChanged(data: JSONObject) {
        val mode = data.optString("mode")
        if (mode.isEmpty()) return
        ThemeManager.set(ThemeMode.fromKey(mode))
        refreshTheme()
    }

    private fun ensureThemeRestored() {
        if (themeRestored) return
        themeRestored = true

        val saved = runCatching {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .getItem(THEME_MODE_KEY)
        }.getOrNull()
        ThemeManager.restore(saved)
    }

    private companion object {

        const val THEME_MODE_KEY = "app_theme_mode"

        var themeRestored = false
    }
}
