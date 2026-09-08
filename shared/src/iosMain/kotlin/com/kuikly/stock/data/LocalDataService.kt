// iOS 平台的本地数据服务实现。

package com.kuikly.stock.data

import platform.Foundation.*

actual fun loadAssetText(path: String): String? {
    val filePath = NSBundle.mainBundle.pathForResource(path, ofType = null) ?: return null
    val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null)
    return nsStr as String?
}

internal actual fun appPrefsGet(key: String): String? = null

internal actual fun appPrefsSet(key: String, value: String) {
}

internal actual fun copyTextToClipboard(text: String) {
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
