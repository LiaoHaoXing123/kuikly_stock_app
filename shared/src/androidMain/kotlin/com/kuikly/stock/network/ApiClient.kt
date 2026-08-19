package com.kuikly.stock.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 平台使用 OkHttp 引擎
 * OkHttp 在真机 WiFi / 企业网络环境下比 CIO 更稳定
 */
actual fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(OkHttp) { config() }
}
