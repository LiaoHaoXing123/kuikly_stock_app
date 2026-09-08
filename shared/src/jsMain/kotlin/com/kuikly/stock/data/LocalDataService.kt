// JS 平台的本地数据服务实现。

package com.kuikly.stock.data

import org.w3c.dom.get
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Promise
import kotlin.js.json

actual fun loadAssetText(path: String): String? {
    val xhr = XMLHttpRequest()
    xhr.open("GET", path, async = false)
    try {
        xhr.send()
        if (xhr.status == 200.toShort()) {
            return xhr.responseText
        }
    } catch (e: dynamic) {
    }
    return null
}

internal actual fun appPrefsGet(key: String): String? = null

internal actual fun appPrefsSet(key: String, value: String) {
}

internal actual fun copyTextToClipboard(text: String) {
}

internal actual fun exportTimestampString(epochMs: Long): String = epochMs.toString()

internal actual fun shareText(title: String, text: String): Boolean = false

internal actual fun saveTextToDownloads(fileName: String, text: String, mimeType: String): String? = null
