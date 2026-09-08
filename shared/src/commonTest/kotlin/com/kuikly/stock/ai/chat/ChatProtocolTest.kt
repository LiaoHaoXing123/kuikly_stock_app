package com.kuikly.stock.ai.chat

import kotlin.test.*

class ChatProtocolTest {
    @Test fun historyKeepsCompleteRecentTurns() {
        val history = listOf("system" to "ignore", "user" to "first", "assistant" to "one", "user" to "second", "assistant" to "two", "user" to "unfinished")
        assertEquals(listOf("user" to "second", "assistant" to "two"), boundedHistory(history, maxChars = 10))
    }
    @Test fun explicitStockOverridesPreviousFocus() {
        val history = listOf("user" to "平安银行", "assistant" to "分析结果")
        val detect: (String) -> List<String> = { when { "茅台" in it -> listOf("600519"); "平安" in it -> listOf("000001"); else -> emptyList() } }
        assertEquals(listOf("000001"), resolveFocus("它最近走势如何", history, detect))
        assertEquals(listOf("600519"), resolveFocus("那茅台呢", history, detect))
        assertTrue(resolveFocus("它呢", emptyList(), detect).isEmpty())
        assertTrue(resolveFocus("你好", history, detect).isEmpty())
    }
    @Test fun validChartRetainsNumbersAndArrays() {
        val result = decodeChatReply("""{"version":1,"text":"走势","cards":[{"type":"chart_card","code":"000001","title":"收盘价","chart_type":"line","data":[{"label":"09-01","value":10.5},{"label":"09-02","value":11.0}]}],"suggestions":[]}""")
        val data = result.cards!!.single()["data"] as List<*>
        assertEquals(10.5, (data.first() as Map<*, *>)["value"])
    }
    @Test fun badCardsAreIsolatedAndReported() {
        val result = decodeChatReply("""{"version":1,"text":"回答","cards":[{"type":"stock_card","code":"bad","name":"股票","price":2},{"type":"chart_card","title":"bad","chart_type":"pie","data":[]}],"suggestions":[]}""")
        assertTrue(result.cards.isNullOrEmpty())
        assertNotNull(result.errorNotice)
        assertEquals("回答", result.text)
    }
    @Test fun invalidEnvelopeDoesNotMasqueradeAsSuccess() {
        assertFailsWith<ChatProtocolException> { decodeChatReply("""{"version":2,"text":"hello","cards":[],"suggestions":[]}""") }
        assertFailsWith<ChatProtocolException> { decodeChatReply("not JSON") }
        assertFailsWith<ChatProtocolException> { decodeChatReply("""{"version":1,"text":12,"cards":[],"suggestions":[]}""") }
    }
    @Test fun partialTextDecodesEscapesWithoutExposingJson() {
        assertEquals("你好\n", partialReplyText("""{"version":1,"text":"你好\n\u4"""))
        assertEquals("你好世界", partialReplyText("""{"text":"你好\u4e16\u754c","cards":[]}"""))
        assertEquals("", partialReplyText("""{"cards":[{"text":"secret"}]}"""))
    }
    @Test fun sseCombinesDataLinesAndIgnoresComments() {
        val decoder = SseDecoder()
        assertNull(decoder.line(": keepalive"))
        assertNull(decoder.line("data: first"))
        assertNull(decoder.line("data: second"))
        assertEquals("first\nsecond", decoder.line(""))
        assertNull(decoder.line(""))
    }
    @Test fun toolArgumentsAreMergedAcrossDeltas() {
        val stream = ChatStreamAccumulator()
        stream.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_stock_quote","arguments":"{\"code\":"}}]}}]}""")
        stream.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"000001\"}"}}]},"finish_reason":"tool_calls"}]}""")
        val tool = stream.toolCalls().single()
        assertEquals("call_1", tool.id)
        assertEquals("get_stock_quote", tool.name)
        assertEquals("{\"code\":\"000001\"}", tool.arguments)
        assertTrue(stream.complete)
    }
    @Test fun truncatedStreamIsNotComplete() {
        val stream = ChatStreamAccumulator()
        stream.accept("""{"choices":[{"delta":{"content":"hello"}}]}""")
        assertFalse(stream.complete)
        stream.accept("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertFailsWith<ChatProtocolException> { stream.requireComplete() }
    }
    @Test fun cardPersistenceRetainsNestedPointsAndNumericTypes() {
        val card = mapOf("type" to "chart_card", "data" to listOf(mapOf("label" to "09-01", "value" to 9.5), mapOf("label" to "09-02", "value" to 10.0)))
        val encoded = nativeToJson(card).toString()
        @Suppress("UNCHECKED_CAST")
        val restored = kotlinx.serialization.json.Json.parseToJsonElement(encoded).toNativeValue() as Map<String, Any?>
        assertEquals(listOf(ChartPoint("09-01", 9.5), ChartPoint("09-02", 10.0)), chartPoints(restored))
    }
    @Test fun unknownFieldsAndStringNumbersAreNotAccepted() {
        val result = decodeChatReply("""{"version":1,"text":"回答","cards":[{"type":"stock_card","code":"000001","name":"平安银行","price":"10","change_percent":"1%"},{"type":"risk_card","content":"研究参考","html":"injected"}],"suggestions":[]}""")
        assertTrue(result.cards.isNullOrEmpty())
        assertNotNull(result.errorNotice)
        assertFalse(result.failed) // The valid text can still enter subsequent context.
    }
    @Test fun fencedAndSurroundedJsonStillParses() {
        val fenced = decodeChatReply("```json\n{\"version\":1,\"text\":\"回答\",\"cards\":[],\"suggestions\":[]}\n```")
        assertEquals("回答", fenced.text)
        val surrounded = decodeChatReply("好的，这是分析：{\"version\":1,\"text\":\"正文\",\"cards\":[],\"suggestions\":[]} 希望对你有帮助")
        assertEquals("正文", surrounded.text)
    }
    @Test fun extraTopLevelFieldsAreTolerated() {
        val result = decodeChatReply("{\"version\":1,\"text\":\"回答\",\"cards\":[],\"suggestions\":[],\"reasoning\":\"brief note\"}")
        assertEquals("回答", result.text)
    }
    @Test fun historyBudgetCannotLeakOtherRolesOrIncompleteTurns() {
        assertTrue(boundedHistory(listOf("assistant" to "orphan", "system" to "injected", "user" to "pending")).isEmpty())
        val history = (1..10).flatMap { listOf("user" to "q$it", "assistant" to "a$it") }
        assertEquals(12, boundedHistory(history).size)
        assertEquals("q5", boundedHistory(history).first().second)
    }
}
