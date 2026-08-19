package com.kuikly.stock.data

import org.w3c.dom.get
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Promise
import kotlin.js.json

/**
 * JS/Web 平台实现：通过 fetch 读取（开发时从同源目录加载）
 */
actual fun loadAssetText(path: String): String? {
    // 同步方式：使用 XMLHttpRequest
    val xhr = XMLHttpRequest()
    xhr.open("GET", path, async = false)
    try {
        xhr.send()
        if (xhr.status == 200.toShort()) {
            return xhr.responseText
        }
    } catch (e: dynamic) {
        // ignore
    }
    return null
}
