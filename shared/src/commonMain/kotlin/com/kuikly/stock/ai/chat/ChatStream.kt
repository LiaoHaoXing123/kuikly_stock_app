// 按 SSE 事件边界拼接文本和工具参数。

package com.kuikly.stock.ai.chat

import kotlinx.serialization.json.*

internal class SseDecoder {
    private val data = mutableListOf<String>()
    private var size = 0
    fun line(line: String): String? {
        if (line.isEmpty()) {
            // SSE 空行结束一个事件，多条 data 行需合并后解析。
            val event = data.takeIf { it.isNotEmpty() }?.joinToString("\n")
            data.clear(); size = 0
            return event
        }
        if (line.startsWith("data:")) {
            val value = line.removePrefix("data:").removePrefix(" ")
            size += value.length
            if (size > 100000) throw ChatProtocolException("流式事件过长")
            data.add(value)
        }
        return null
    }
}

internal data class ChatToolCall(val id: String, val name: String, val arguments: String)

internal class ChatStreamAccumulator {
    private val content = StringBuilder()
    private val calls = mutableMapOf<Int, ChatToolCall>()
    private var finishReason: String? = null
    val text: String get() = content.toString()
    val complete: Boolean get() = finishReason == "stop" || finishReason == "tool_calls"

    fun accept(event: String) {
        if (event == "[DONE]") return
        val root = Json.parseToJsonElement(event).jsonObject
        if (root["error"] != null) throw ChatProtocolException("服务在生成过程中返回错误，请重试")
        val choices = root["choices"] as? JsonArray ?: return
        val choice = choices.firstOrNull()?.jsonObject ?: return
        val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (reason != null) finishReason = reason
        val delta = choice["delta"] as? JsonObject ?: return
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.let { content.append(it) }
        if (content.length > 100000) throw ChatProtocolException("AI 响应过长")
        (delta["tool_calls"] as? JsonArray)?.forEach {
            val call = it.jsonObject
            val index = call["index"]?.jsonPrimitive?.intOrNull ?: throw ChatProtocolException("工具调用缺少索引")
            if (index !in 0..7) throw ChatProtocolException("工具调用数量超出限制")
            val old = calls[index] ?: ChatToolCall("", "", "")
            // 同一工具调用的参数可能分散在多个流式片段中。
            val function = call["function"] as? JsonObject
            val next = ChatToolCall(
                old.id + (call["id"]?.jsonPrimitive?.contentOrNull ?: ""),
                old.name + (function?.get("name")?.jsonPrimitive?.contentOrNull ?: ""),
                old.arguments + (function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""),
            )
            if (next.arguments.length > 10000 || next.name.length > 100 || next.id.length > 200) throw ChatProtocolException("工具调用参数过长")
            calls[index] = next
        }
    }

    fun requireComplete() {
        if (!complete) throw ChatProtocolException(if (finishReason == "length") "回答超出长度限制，请缩小问题范围后重试" else "连接中断，回答未完整接收，请重试")
    }

    fun toolCalls(): List<ChatToolCall> = calls.entries.sortedBy { it.key }.map { it.value }.also { list ->
        if (list.any { it.id.isBlank() || it.name.isBlank() }) throw ChatProtocolException("工具调用不完整")
    }
}
