@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kuikly.stock.data

import platform.Foundation.*
import platform.UIKit.UIPasteboard

actual fun loadAssetText(path: String): String? {
    val filePath = NSBundle.mainBundle.pathForResource(path, ofType = null) ?: return null
    val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null)
    return nsStr as String?
}

private val userDefaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

internal actual fun appPrefsGet(key: String): String? = try {
    userDefaults.stringForKey(key)
} catch (e: Throwable) {
    null
}

internal actual fun appPrefsSet(key: String, value: String) {
    try {
        userDefaults.setObject(value, forKey = key)
    } catch (e: Throwable) {

    }
}

internal actual fun copyTextToClipboard(text: String) {
    try {
        UIPasteboard.generalPasteboard.string = text
    } catch (e: Throwable) {

    }
}

internal actual fun exportTimestampString(epochMs: Long): String = try {
    val fmt = NSDateFormatter()
    fmt.dateFormat = "yyyy-MM-dd HH:mm"
    fmt.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
} catch (e: Throwable) {
    ""
}

internal actual fun shareText(title: String, text: String): Boolean = false

internal actual fun saveTextToDownloads(fileName: String, text: String, mimeType: String): String? = null
