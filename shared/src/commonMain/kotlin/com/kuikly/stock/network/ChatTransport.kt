package com.kuikly.stock.network

import com.kuikly.stock.ai.chat.*
import com.kuikly.stock.ai.config.*
import com.kuikly.stock.ai.tool.StockTools
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import com.kuikly.stock.data.nowMillis

internal object ChatTransport {
    internal suspend fun generate(
        config: AiRequestConfig,
        prompt: List<Pair<String, String>>,
        onText: suspend (String) -> Unit,
        onStage: suspend (String) -> Unit,
    ): String {
        val messages = prompt.map { (role, content) -> buildJsonObject { put("role", role); put("content", content) } }.toMutableList()
        var toolsEnabled = config.toolsEnabled
        var streaming = true
        var toolRounds = 0
        while (true) {
            onStage(if (streaming) "正在生成回答…" else "服务不支持流式，正在等待完整回答…")
            val body = buildJsonObject {
                put("model", config.model)
                put("temperature", DeepSeekConfig.TEMPERATURE)
                put("max_tokens", DeepSeekConfig.MAX_TOKENS)
                put("stream", streaming)
                put("messages", JsonArray(messages))
                if (toolsEnabled && toolRounds < 2) put("tools", StockTools.definitions)
            }
            val reply = try {
                request(config, body, onText)
            } catch (unsupported: UnsupportedChatFeature) {
                if (unsupported.feature == "stream" && streaming) { streaming = false; continue }
                if (unsupported.feature == "tools" && toolsEnabled) { toolsEnabled = false; continue }
                throw unsupported
            }
            val calls = reply.calls
            if (calls.isEmpty()) return reply.text
            if (toolRounds++ >= 2) throw ChatProtocolException("工具查询次数过多，请缩小问题范围")
            onText("")
            messages.add(buildJsonObject {
                put("role", "assistant")
                put("content", reply.text.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
                put("tool_calls", buildJsonArray {
                    calls.forEach { call -> add(buildJsonObject {
                        put("id", call.id); put("type", "function")
                        put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments) })
                    }) }
                })
            })
            for (call in calls) {
                onStage(when (call.name) {
                    "get_stock_kline" -> "正在读取 K 线数据…"
                    "get_stock_indicator" -> "正在读取技术指标…"
                    "get_market_overview" -> "正在读取市场概览…"
                    else -> "正在查询个股行情…"
                })
                val args = try { Json.parseToJsonElement(call.arguments).jsonObject } catch (_: Exception) {
                    throw ChatProtocolException("模型返回了无效工具参数，请重试")
                }
                val result = StockTools.execute(call.name, args)
                messages.add(buildJsonObject {
                    put("role", "tool"); put("tool_call_id", call.id); put("content", result.toString())
                })
            }
        }
    }

    private suspend fun request(config: AiRequestConfig, body: JsonObject, onText: suspend (String) -> Unit): TransportReply =
        ApiClient.client.preparePost(config.endpoint) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            header(HttpHeaders.Accept, "text/event-stream, application/json")
            setBody(body.toString())
        }.execute { response ->
            if (response.status.value !in 200..299) {
                val error = response.bodyAsText().take(2000).lowercase()
                val unsupported = listOf("unsupported", "not support", "not allowed", "unknown parameter", "unrecognized", "不支持").any { it in error }
                if (response.status.value in listOf(400, 422, 501) && unsupported) {
                    if ("stream" in error) throw UnsupportedChatFeature("stream")
                    if ("tool" in error || "function" in error) throw UnsupportedChatFeature("tools")
                }
                throw AiProviderException(response.status.value, providerErrorMessage(response.status.value))
            }
            if (response.contentType()?.match(ContentType.Text.EventStream) != true) {

                val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                    ?: throw ChatProtocolException("服务响应缺少 choices")
                val reason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (reason != "stop" && reason != "tool_calls") throw ChatProtocolException("服务未返回完整回答")
                val message = choice["message"]?.jsonObject ?: throw ChatProtocolException("服务响应缺少 message")
                val text = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (text.length > 100000) throw ChatProtocolException("AI 响应过长")
                val calls = (message["tool_calls"] as? JsonArray).orEmpty().map { entry ->
                    // 与流式分支（ChatStreamAccumulator）保持同等严谨：网关可能返回残缺结构，
                    // 这里必须抛可诊断的 ChatProtocolException，而不是 NPE / kotlinx 内部异常
                    // （后者会把 "Element class ... is not a JsonObject" 这种内部实现细节透给用户）。
                    val call = entry as? JsonObject ?: throw ChatProtocolException("服务返回的 tool_calls 元素不是对象")
                    val function = call["function"] as? JsonObject
                        ?: throw ChatProtocolException("服务返回的工具调用缺少 function")
                    val id = call["id"]?.jsonPrimitive?.contentOrNull
                        ?: throw ChatProtocolException("服务返回的工具调用缺少 id")
                    val name = function["name"]?.jsonPrimitive?.contentOrNull
                        ?: throw ChatProtocolException("服务返回的工具调用缺少 function.name")
                    val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull
                        ?: throw ChatProtocolException("服务返回的工具调用缺少 function.arguments")
                    ChatToolCall(id, name, arguments)
                }
                if (calls.size > 8) throw ChatProtocolException("工具调用数量超出限制")
                onText(partialReplyText(text))
                return@execute TransportReply(text, calls)
            }
            val channel = response.bodyAsChannel()
            val decoder = SseDecoder()
            val stream = ChatStreamAccumulator()
            var published = ""
            var lastUpdate = 0L
            while (true) {
                val line = channel.readUTF8Line(100000) ?: break
                val event = decoder.line(line) ?: continue
                if (event == "[DONE]") break
                stream.accept(event)
                val now = nowMillis()
                if (now - lastUpdate >= 60 || stream.complete) {
                    val preview = partialReplyText(stream.text)
                    if (preview != published) { onText(preview); published = preview }
                    lastUpdate = now
                }
            }
            stream.requireComplete()
            val preview = partialReplyText(stream.text)
            if (preview != published) onText(preview)
            TransportReply(stream.text, stream.toolCalls())
        }
}

private data class TransportReply(val text: String, val calls: List<ChatToolCall>)
private class UnsupportedChatFeature(val feature: String) : IllegalStateException("服务不支持 $feature")
