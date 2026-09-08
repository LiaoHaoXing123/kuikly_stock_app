package com.kuikly.stock.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionExportTest {
    private fun sampleSession() = ChatSession(
        id = "s1",
        title = "贵州茅台怎么看？",
        messages = listOf(
            ChatMessageItem(role = "user", content = "贵州茅台怎么看？", isUser = true),
            ChatMessageItem(
                role = "assistant",
                content = "整体偏强，短期震荡向上。",
                isUser = false,
                cards = listOf(
                    mapOf(
                        "type" to "stock_card", "name" to "贵州茅台", "code" to "600519",
                        "price" to 1820.5, "changePercent" to 1.23
                    ),
                    mapOf(
                        "type" to "conclusion_card", "bias" to "偏强",
                        "one_liner" to "短期震荡向上", "action" to "持有"
                    ),
                    mapOf(
                        "type" to "chart_card", "title" to "贵州茅台·收盘走势", "unit" to "元",
                        "data" to listOf(
                            mapOf("label" to "09-01", "value" to 1800.0),
                            mapOf("label" to "09-02", "value" to 1820.5)
                        )
                    ),
                    mapOf(
                        "type" to "compare_card", "title" to "白酒三巨头",
                        "headers" to "名称|现价|涨幅",
                        "rows" to listOf("贵州茅台|1820.50|+1.23%", "五粮液|150.20|+0.80%")
                    )
                ),
                suggestions = listOf("估值贵吗？", "加仓点在哪？")
            )
        ),
        updatedAt = 0L
    )

    @Test
    fun markdownContainsTurnsAndCards() {
        val md = exportSessionMarkdown(sampleSession(), "2026-09-08 12:00")
        assertTrue(md.contains("# 贵州茅台怎么看？"))
        assertTrue(md.contains("2026-09-08 12:00"))
        assertTrue(md.contains("## 🙋 我"))
        assertTrue(md.contains("## 🤖 AI 助手"))
        assertTrue(md.contains("[个股] 贵州茅台(600519)"))
        assertTrue(md.contains("+1.23%"))
        assertTrue(md.contains("[结论] 偏强"))
        assertTrue(md.contains("追问"))
        assertTrue(md.contains("不构成投资建议"))
    }

    @Test
    fun markdownRendersDataTables() {
        val md = exportSessionMarkdown(sampleSession(), "2026-09-08 12:00")
        assertTrue(md.contains("| 09-01 | 1800.00 |"))
        assertTrue(md.contains("| 名称 | 现价 | 涨幅 |"))
        assertTrue(md.contains("五粮液"))
    }

    @Test
    fun markdownMarksFailedReplies() {
        val s = ChatSession(
            id = "f", title = "t",
            messages = listOf(ChatMessageItem(role = "assistant", content = "半截回复", isUser = false, failed = true)),
            updatedAt = 0L
        )
        assertTrue(exportSessionMarkdown(s, "").contains("发送失败"))
    }

    @Test
    fun fileBaseSanitizes() {
        assertEquals("chat", exportFileBase("   "))
        assertEquals("AB", exportFileBase("A/B:C"))
        assertTrue(exportFileBase("很长很长很长很长很长很长很长很长很长的标题").length <= 24)
    }

    @Test
    fun jsonRoundTrips() {
        val json = exportSessionJson(sampleSession(), "2026-09-08 12:00")
        val el = Json.parseToJsonElement(json).jsonObject
        assertEquals(2, el["messageCount"]?.jsonPrimitive?.int)
        assertEquals("贵州茅台怎么看？", el["title"]?.jsonPrimitive?.content)
        assertEquals("chat-session/1", el["format"]?.jsonPrimitive?.content)
    }
}
