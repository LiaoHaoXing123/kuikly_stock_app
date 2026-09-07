package com.kuikly.stock.data

import android.content.ClipData
import android.content.Context

internal actual fun copyTextToClipboard(text: String) {
    val ctx = stockDbContext() ?: return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    try {
        cm.setPrimaryClip(ClipData.newPlainText("kuikly_stock", text))
    } catch (e: Throwable) {
    }
}
