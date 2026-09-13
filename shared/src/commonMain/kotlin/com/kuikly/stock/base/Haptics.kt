package com.kuikly.stock.base

internal enum class HapticStyle(val raw: String) {

    Light("light"),

    Medium("medium"),

    Heavy("heavy"),
}

internal fun hapticTick(style: HapticStyle = HapticStyle.Light) {
    val module = Utils.currentBridgeModuleOrNull() ?: return
    runCatching { module.vibrate(style.raw) }
}
