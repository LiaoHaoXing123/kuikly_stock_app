package com.kuikly.stock.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Android 平台使用 OkHttp 引擎，强制 IPv4 避免局域网 IPv6 超时
 *
 * 问题背景：
 * - 电脑同时有 IPv4 (10.160.9.90) 和 IPv6 (fe80::...) 地址
 * - OkHttp 默认会先尝试 IPv6，Android 真机上 IPv6 连接内网经常超时
 * - 手机浏览器走系统网络栈（可能优先 IPv4），所以浏览器能通但 App 不行
 *
 * 解决方案：自定义 DNS 解析，只返回 IPv4 地址
 */
actual fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(OkHttp) {
        config()

        engine {
            // 配置底层 OkHttpClient
            config {
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
