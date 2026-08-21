package com.kuikly.stock.network

import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * HTTP 客户端配置
 * 使用 Ktor Client 进行网络请求
 *
 * 平台引擎通过 expect/actual 显式指定，避免依赖 ServiceLoader 在真机上的不确定性：
 * - Android: OkHttp（ktor-client-okhttp）
 * - iOS: Darwin（ktor-client-darwin）
 * - JS: Js（ktor-client-js）
 *
 * 【重要】不安装 Ktor 的 HttpTimeout 插件：
 * Ktor 的 HttpTimeout 用协程 withTimeout 实现，无法中断 OkHttp 底层阻塞的 Socket read
 * （原生系统调用不可协作中断）。且对于 OkHttp 引擎，Ktor 会在请求执行时把
 * socketTimeoutMillis 动态写入 OkHttp 的 readTimeout，覆盖 engine config 里的设置，
 * 导致实际超时变成 90 秒而非预期的 30 秒。
 * 超时完全由各平台原生引擎负责（Android: OkHttp connectTimeout/readTimeout）。
 *
 * 【重要】不安装 Logging 插件：
 * LogLevel.ALL 会完整记录请求/响应体，在大响应体或慢连接时可能加剧阻塞或死锁。
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
            // 超时由各平台原生引擎设置（见 androidMain/iosMain/jsMain 的 actual 实现）
            // 日志由平台原生 Logging 处理，不在这里安装 Ktor Logging 插件
        }
    }

    // 全局客户端实例（懒加载；支持重置以清掉异常连接池）
    private var clientInstance: HttpClient? = null

    val client: HttpClient
        get() {
            if (clientInstance == null) {
                clientInstance = createClient()
            }
            return clientInstance!!
        }

    /**
     * 关闭并重置 HTTP 客户端。
     * 当请求长时间卡住（连接池/连接状态异常）时调用，下次访问会自动重建全新客户端。
     */
    fun recreate() {
        clientInstance?.close()
        clientInstance = null
    }
}
