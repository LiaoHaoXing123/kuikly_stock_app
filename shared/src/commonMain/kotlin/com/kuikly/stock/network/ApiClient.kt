package com.kuikly.stock.network

import kotlinx.serialization.json.Json
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json

/**
 * HTTP 客户端配置
 * 使用 Ktor Client 进行网络请求
 */
object ApiClient {

    // JSON 配置
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 创建 Ktor HttpClient 实例
     *
     * 引擎按平台自动选择（由各平台 sourceSet 的 Ktor 引擎依赖决定）：
     * - Android/JVM: CIO（ktor-client-cio）
     * - iOS/Native: Darwin（ktor-client-darwin）
     * - JS: JsClient（ktor-client-js）
     * commonMain 不硬编码引擎，避免引入平台不兼容的引擎（如 CIO 不支持 JS）。
     */
    fun createClient(): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(Logging) {
                // 开发环境启用日志
            }
        }
    }

    // 全局客户端实例（懒加载）
    val client: HttpClient by lazy { createClient() }
}
