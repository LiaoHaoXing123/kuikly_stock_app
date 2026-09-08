package com.kuikly.stock.data

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun exportTimestampString(epochMs: Long): String = try {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
} catch (e: Throwable) {
    ""
}

internal actual fun shareText(title: String, text: String): Boolean = try {
    val ctx = stockDbContext() ?: return false
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Throwable) {
    false
}

internal actual fun saveTextToDownloads(fileName: String, text: String, mimeType: String): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        val ctx = stockDbContext() ?: return null
        val resolver = ctx.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: return null
        val wrote = try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Throwable) {
            false
        }
        if (!wrote) {
            resolver.delete(uri, null, null)
            return null
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        fileName
    } catch (e: Throwable) {
        null
    }
}
