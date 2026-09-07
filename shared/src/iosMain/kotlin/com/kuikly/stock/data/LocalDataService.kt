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
