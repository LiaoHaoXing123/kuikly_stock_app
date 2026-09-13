package com.kuikly.stock.data

import android.content.Context

private var appContext: Context? = null

fun initLocalDataService(context: Context) {
    appContext = context.applicationContext
}

actual fun loadAssetText(path: String): String? {
    val ctx = appContext ?: return null
    return try {
        ctx.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }
}
