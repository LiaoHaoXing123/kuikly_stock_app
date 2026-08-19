package com.kuikly.stock.data

import platform.Foundation.*

/**
 * iOS/Native 平台实现：从 Bundle.main 读取
 */
actual fun loadAssetText(path: String): String? {
    val filePath = NSBundle.mainBundle.pathForResource(path, ofType = null) ?: return null
    val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null)
    return nsStr as String?
}
