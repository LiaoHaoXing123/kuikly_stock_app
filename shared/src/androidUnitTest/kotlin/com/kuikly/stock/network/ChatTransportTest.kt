package com.kuikly.stock.network

import com.kuikly.stock.ai.chat.ChatProtocolException
import com.kuikly.stock.ai.config.AiProviderException
import com.kuikly.stock.ai.config.AiRequestConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatTransportTest {
    private val answer = """{"version":1,"text":"你好世界","cards":[],"suggestions":[]}"""
    private fun event(content: String = "", finish: String? = null): String = "data: " + buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", content) })
            put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
        }) })
    } + "\n\n"

    private fun HttpExchange.reply(body: String, status: Int = 200, type: String = "text/event-stream") {
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, 0)
        responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun server(handler: (HttpExchange) -> Unit, block: suspend (AiRequestConfig) -> Unit) {
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = pool
        server.createContext("/chat/completions") { exchange -> handler(exchange) }
        server.start()
        try {
            runBlocking { withTimeout(15000) {
                block(AiRequestConfig("test", "test", "http://127.0.0.1:${server.address.port}/chat/completions", "test", "test-key", false))
            } }
        } finally { server.stop(0); pool.shutdownNow() }
    }

    @Test fun deliversPreviewBeforeServerFinishes() {
        val previewSeen = CountDownLatch(1)
        var arrivedEarly = false
        server({ exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write(event("""{"version":1,"text":"你好""").toByteArray(Charsets.UTF_8)); output.flush()
                arrivedEarly = previewSeen.await(5, TimeUnit.SECONDS)
                output.write((event("""世界","cards":[],"suggestions":[]}""", "stop") + "data: [DONE]\n\n").toByteArray(Charsets.UTF_8))
            }
        }) { config ->
            val raw = ChatTransport.generate(config, listOf("user" to "hi"), { if (it == "你好") previewSeen.countDown() }, {})
            assertEquals(answer, raw)
            assertTrue(arrivedEarly, "The preview must arrive while the HTTP response is still open")
        }
    }

    @Test fun unsupportedStreamingFallsBackOnce() {
        var requests = 0
        server({ exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            requests++
            if (requests == 1) exchange.reply("""{"error":"stream is not supported"}""", 400, "application/json")
            else {
                assertEquals(false, request["stream"]!!.jsonPrimitive.boolean)
                exchange.reply(buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("finish_reason", "stop"); put("message", buildJsonObject { put("content", answer) })
                    }) })
                }.toString(), type = "application/json")
            }
        }) { config ->
            assertEquals(answer, ChatTransport.generate(config, listOf("user" to "hi"), {}, {}))
            assertEquals(2, requests)
        }
    }

    @Test fun authenticationFailureIsNotRetried() {
        var requests = 0
        server({ exchange -> requests++; exchange.reply("unauthorized", 401, "application/json") }) { config ->
            assertFailsWith<AiProviderException> { ChatTransport.generate(config, listOf("user" to "hi"), {}, {}) }
            assertEquals(1, requests)
        }
    }

    @Test fun truncatedBodyFailsInsteadOfSavingHalfAReply() {
        server({ exchange -> exchange.reply(event("""{"version":1,"text":"half""")) }) { config ->
            assertFailsWith<ChatProtocolException> { ChatTransport.generate(config, listOf("user" to "hi"), {}, {}) }
        }
    }

    @Test fun toolRoundPreservesHistoryAndCallIds() {
        var requests = 0
        var receivedToolResult = false
        server({ exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            requests++
            if (requests == 1) {
                exchange.reply("data: " + """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_test","function":{"name":"unknown_test_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n")
            } else {
                val messages = request["messages"]!!.jsonArray
                receivedToolResult = messages[0].jsonObject["content"]!!.jsonPrimitive.content == "分析平安银行" &&
                    messages.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content == "call_test"
                exchange.reply(event(answer, "stop") + "data: [DONE]\n\n")
            }
        }) { config ->
            assertEquals(answer, ChatTransport.generate(config.copy(toolsEnabled = true), listOf("user" to "分析平安银行", "assistant" to "之前的回答", "user" to "它呢"), {}, {}))
            assertEquals(2, requests)
            assertTrue(receivedToolResult)
        }
    }
}
