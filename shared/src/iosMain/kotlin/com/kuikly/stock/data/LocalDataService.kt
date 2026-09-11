@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

// iOS 平台的本地数据服务实现。

package com.kuikly.stock.data

import platform.Foundation.*
import platform.UIKit.UIPasteboard

actual fun loadAssetText(path: String): String? {
    val filePath = NSBundle.mainBundle.pathForResource(path, ofType = null) ?: return null
    val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null)
    return nsStr as String?
}

private val userDefaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

// 会话 / 自选 / 提醒 / API 配置等持久化：NSUserDefaults
internal actual fun appPrefsGet(key: String): String? = try {
    userDefaults.stringForKey(key)
} catch (e: Throwable) {
    null
}

internal actual fun appPrefsSet(key: String, value: String) {
    try {
        userDefaults.setObject(value, forKey = key)
    } catch (e: Throwable) {
        // 写入失败可忽略（调用方均有内存态）
    }
}

internal actual fun copyTextToClipboard(text: String) {
    try {
        UIPasteboard.generalPasteboard.string = text
    } catch (e: Throwable) {
        // 复制失败可忽略
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
