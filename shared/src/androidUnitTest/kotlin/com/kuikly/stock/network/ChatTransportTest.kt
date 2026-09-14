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

    /** 构造非 SSE 分支的响应体：服务忽略 stream，直接返回带 tool_calls 的 JSON。 */
    private fun nonStreamingToolCallReply(entry: JsonObject) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", "tool_calls")
            put("message", buildJsonObject {
                put("content", "")
                put("tool_calls", buildJsonArray { add(entry) })
            })
        }) })
    }.toString()

    private fun probeMalformedToolCall(body: String): Throwable =
        runBlocking {
            var thrown: Throwable? = null
            try {
                server({ exchange -> exchange.requestBody.readBytes(); exchange.reply(body, type = "application/json") }) { config ->
                    ChatTransport.generate(config, listOf("user" to "hi"), {}, {})
                }
            } catch (e: Throwable) {
                thrown = e
            }
            thrown ?: fail("畸形 tool_call 竟然没有抛异常（说明被静默吞掉了）")
        }

    @Test fun toolCallMissingFunctionRaisesProtocolError() {
        val entry = buildJsonObject { put("type", "function") } // 缺 id、缺 function
        val e = probeMalformedToolCall(nonStreamingToolCallReply(entry))
        println("[probe] missing-function -> ${e::class.qualifiedName}: ${e.message}")
        assertTrue(
            e is ChatProtocolException,
            "畸形 tool_call 应抛 ChatProtocolException（可诊断），实际抛了 ${e::class.qualifiedName}: ${e.message}",
        )
    }

    @Test fun toolCallEntryNotAnObjectRaisesProtocolError() {
        val body = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "tool_calls")
                put("message", buildJsonObject {
                    put("content", "")
                    put("tool_calls", buildJsonArray { add(JsonPrimitive("not-an-object")) })
                })
            }) })
        }.toString()
        val e = probeMalformedToolCall(body)
        println("[probe] non-object -> ${e::class.qualifiedName}: ${e.message}")
        assertTrue(
            e is ChatProtocolException,
            "tool_calls 元素不是对象时应抛 ChatProtocolException，实际抛了 ${e::class.qualifiedName}: ${e.message}",
        )
    }

    /** 回归守卫：非流式分支下结构完整的 tool_call 仍应正常解析并进入工具轮。 */
    @Test fun wellFormedNonStreamingToolCallStillRunsTheToolRound() {
        var requests = 0
        var secondRequestSeesToolResult = false
        server({ exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            requests++
            if (requests == 1) {
                exchange.reply(nonStreamingToolCallReply(buildJsonObject {
                    put("id", "call_ok")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "unknown_test_tool"); put("arguments", "{}")
                    })
                }), type = "application/json")
            } else {
                secondRequestSeesToolResult =
                    request["messages"]!!.jsonArray.any { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "tool" }
                exchange.reply(event(answer, "stop") + "data: [DONE]\n\n")
            }
        }) { config ->
            assertEquals(answer, ChatTransport.generate(config.copy(toolsEnabled = true), listOf("user" to "hi"), {}, {}))
            assertEquals(2, requests, "结构完整的 tool_call 应触发第二轮请求")
            assertTrue(secondRequestSeesToolResult, "第二轮请求应带上 role=tool 的工具结果")
        }
    }
}
