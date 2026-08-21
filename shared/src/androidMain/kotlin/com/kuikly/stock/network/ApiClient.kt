package com.kuikly.stock.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Android 平台使用 OkHttp 引擎，强制 IPv4 避免局域网 IPv6 超时
 *
 * 问题背景：
 * - 电脑同时有 IPv4 (10.160.9.90) 和 IPv6 (fe80::...) 地址
 * - OkHttp 默认会先尝试 IPv6，Android 真机上 IPv6 连接内网经常超时
 * - 手机浏览器走系统网络栈（可能优先 IPv4），所以浏览器能通但 App 不行
 *
 * 解决方案：自定义 DNS 解析，只返回 IPv4 地址
 *
 * 【关键修复】必须在 OkHttp 引擎层显式设置超时：
 * Ktor 的 HttpTimeout 插件用协程 withTimeout 实现，无法中断 OkHttp 底层
 * 阻塞的 Socket connect（目标不可达时 SYN 重试可长达 127 秒），导致协程
 * 永久挂起、withTimeout 失效、UI 永远显示"正在检测/正在思考"。
 * 在 OkHttpClient.Builder 上设置的超时是底层 Socket 级别的，能真正生效。
 */
actual fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(OkHttp) {
        config()

        engine {
            // 配置底层 OkHttpClient
            config {
                // 绕过系统 HTTP 代理直连局域网：
                // 手机若开了系统代理，OkHttp 会走代理导致请求卡死，而浏览器可能直连
                proxy(java.net.Proxy.NO_PROXY)

                // ===== 【关键】OkHttp 底层超时（Socket SO_TIMEOUT 级别，能真正中断阻塞 read）=====
                // Ktor 的 withTimeout 无法中断 OkHttp 底层阻塞的 Socket read（原生系统调用不可协作中断）。
                // 如果服务器发了响应头但响应体没正确结束，bodyAsText() 会永久挂起，withTimeout 无效。
                // 必须靠 OkHttp 的 Socket SO_TIMEOUT 才能在超时后抛出 SocketTimeoutException，真正中断。
                connectTimeout(10, TimeUnit.SECONDS)  // 连不上 10 秒快速失败
                readTimeout(30, TimeUnit.SECONDS)     // 响应体读取 30 秒超时（AI 回答最长约 15 秒，30 秒留足余量）
                writeTimeout(30, TimeUnit.SECONDS)    // 请求体写入 30 秒超时
                // 禁止连接失败后自动重试（避免静默重试延长等待时间）
                retryOnConnectionFailure(false)
                // ========================================================================================

                // 强制 IPv4：自定义 DNS 只返回 A 记录（IPv4），过滤 AAAA（IPv6）
                dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
                        return addresses.filterIsInstance<Inet4Address>()
                            .ifEmpty { addresses } // 如果没有 IPv4 则回退全部
                    }
                })
            }
        }
    }
}
