package com.kuikly.stock.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Inet4Address
import java.net.UnknownHostException
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private val DNS_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()

actual fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(OkHttp) {
        config()

        engine {
            config {
                proxy(java.net.Proxy.NO_PROXY)

                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(false)

                dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        val future: Future<List<InetAddress>> = DNS_EXECUTOR.submit<List<InetAddress>> {
                            okhttp3.Dns.SYSTEM.lookup(hostname)
                        }
                        return try {
                            val addresses = future.get(5, TimeUnit.SECONDS)
                            addresses.filterIsInstance<Inet4Address>().ifEmpty { addresses }
                        } catch (e: Exception) {
                            future.cancel(true)
                            throw UnknownHostException(hostname)
                        }
                    }
                })
            }
        }
    }
}
