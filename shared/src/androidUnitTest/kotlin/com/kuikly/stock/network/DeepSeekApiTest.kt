package com.kuikly.stock.network

import com.kuikly.stock.ai.config.AiProviderProfile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * 「API 配置」页的连接测试会把异常文案直接展示给用户，
 * 所以 AI 返回畸形结构时必须落到可读的中文提示，而不是 IndexOutOfBounds
 * 或 kotlinx 内部类型名。
 */
class DeepSeekApiTest {

    private fun HttpExchange.reply(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, 0)
        responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun withServer(body: String, block: suspend (AiProviderProfile) -> Unit) {
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = pool
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            exchange.reply(body)
        }
        server.start()
        try {
            runBlocking {
                withTimeout(15000) {
                    block(
                        AiProviderProfile(
                            id = "test", name = "test",
                            baseUrl = "http://127.0.0.1:${server.address.port}",
                            model = "test-model", toolsEnabled = false,
                        )
                    )
                }
            }
        } finally {
            server.stop(0); pool.shutdownNow()
        }
    }

    private fun assertNoInternalsLeaked(message: String, vararg forbidden: String) {
        for (needle in forbidden) {
            assertFalse(
                message.contains(needle, ignoreCase = true),
                "错误文案泄露了内部细节（含「$needle」）：$message",
            )
        }
    }

    @Test fun emptyChoicesYieldsReadableMessage() {
        withServer("""{"choices":[]}""") { profile ->
            val result = DeepSeekApi.testConnection(profile, "test-key")
            assertFalse(result.success)
            assertNoInternalsLeaked(result.message, "Index", "out of bounds", "ArrayIndex", "java.")
        }
    }

    @Test fun nonObjectChoiceYieldsReadableMessage() {
        withServer("""{"choices":["nope"]}""") { profile ->
            val result = DeepSeekApi.testConnection(profile, "test-key")
            assertFalse(result.success)
            assertNoInternalsLeaked(result.message, "is not a JsonObject", "kotlinx", "JsonLiteral")
        }
    }

    @Test fun missingMessageYieldsReadableMessage() {
        withServer("""{"choices":[{"message":null}]}""") { profile ->
            val result = DeepSeekApi.testConnection(profile, "test-key")
            assertFalse(result.success)
            assertNoInternalsLeaked(result.message, "Index", "NullPointerException", "is not a JsonObject")
        }
    }

    @Test fun validReplyReportsSuccess() {
        withServer("""{"choices":[{"message":{"content":"OK"}}]}""") { profile ->
            val result = DeepSeekApi.testConnection(profile, "test-key")
            assertTrue(result.success, "合法响应应判定连接成功，实际 message=${result.message}")
        }
    }

    /** 网关/代理出错时常见返回 HTML 错误页而不是 JSON。 */
    @Test fun nonJsonBodyYieldsReadableMessage() {
        withServer("<html><body>502 Bad Gateway</body></html>") { profile ->
            val result = DeepSeekApi.testConnection(profile, "test-key")
            assertFalse(result.success)
            assertNoInternalsLeaked(
                result.message,
                "SerializationException", "kotlinx", "Unexpected JSON token", "JsonDecodingException",
            )
        }
    }
}
