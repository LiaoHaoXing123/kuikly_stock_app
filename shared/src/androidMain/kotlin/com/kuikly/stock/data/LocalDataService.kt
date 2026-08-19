package com.kuikly.stock.data

import android.content.Context

/**
 * Android 平台实现：通过 Context.assets 读取文件
 *
 * 使用方式：在 Application.onCreate() 中调用
 *   LocalDataService.init(androidContext)
 */
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
