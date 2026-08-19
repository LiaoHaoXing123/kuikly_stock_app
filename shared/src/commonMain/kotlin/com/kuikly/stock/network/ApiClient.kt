package com.kuikly.stock.network

import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json

/**
 * HTTP 客户端配置
 * 使用 Ktor Client 进行网络请求
 *
 * 平台引擎通过 expect/actual 显式指定，避免依赖 ServiceLoader 在真机上的不确定性：
 * - Android: OkHttp（ktor-client-okhttp）
 * - iOS: Darwin（ktor-client-darwin）
 * - JS: Js（ktor-client-js）
 */
expect fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

object ApiClient {

    // JSON 配置
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 创建 Ktor HttpClient 实例
     */
    fun createClient(): HttpClient {
        return createHttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 30000
            }
            install(Logging) {
                level = LogLevel.ALL
            }
        }
    }

    // 全局客户端实例（懒加载）
    val client: HttpClient by lazy { createClient() }
}
