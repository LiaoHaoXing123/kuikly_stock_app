// 全局网络客户端：统一的超时与 JSON 解析配置，AI 与后端请求共用。

package com.kuikly.stock.network

import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json

expect fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

object ApiClient {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun createClient(): HttpClient {
        return createHttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }
    }

    private var clientInstance: HttpClient? = null

    val client: HttpClient
    get() {
        if (clientInstance == null) {
            clientInstance = createClient()
            }
        return clientInstance!!
        }

    fun recreate() {
        clientInstance?.close()
        clientInstance = null
    }
}
