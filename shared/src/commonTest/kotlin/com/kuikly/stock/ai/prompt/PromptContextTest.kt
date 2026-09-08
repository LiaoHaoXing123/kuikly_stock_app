package com.kuikly.stock.ai.prompt

import com.kuikly.stock.pages.StockListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptContextTest {

    private val msg = "帮我看看000001"
    private val s = StockListItem("000001", "平安银行")

    @Test
    fun empty_hasNoData_andRendersEmpty() {
        val c = ChatPromptContext.empty(msg, listOf(s))
        assertFalse(c.hasData)
        assertEquals("", c.render())
    }

    @Test
    fun stockLines_renderWithHeader() {
        val c = ChatPromptContext(msg, listOf(s), listOf("- 平安银行: 最新价 11.59"), emptyList())
        assertTrue(c.hasData)
        val out = c.render()
        assertTrue(out.contains("相关股票数据："))
        assertTrue(out.contains("最新价 11.59"))
    }

    @Test
    fun marketLines_areAppended() {
        val c = ChatPromptContext(msg, listOf(s), emptyList(), listOf("- 全市场概览: 共 5200 只"))
        assertTrue(c.hasData)
        assertTrue(c.render().contains("全市场概览"))
    }

    @Test
    fun stockAndMarket_composeTogether() {
        val c = ChatPromptContext(msg, listOf(s), listOf("- 平安银行: ..."), listOf("- 涨幅榜: ..."))
        val out = c.render()
        assertTrue(out.contains("平安银行"))
        assertTrue(out.contains("涨幅榜"))
    }

    @Test
    fun indexLines_renderWithHeader() {
        val idx = StockListItem("000001", "上证指数", isIndex = true)
        val c = ChatPromptContext(msg, listOf(idx), emptyList(), emptyList(), listOf("- 上证指数: 最新点位 3800.5"))
        assertTrue(c.hasData)
        val out = c.render()
        assertTrue(out.contains("相关指数数据："))
        assertTrue(out.contains("最新点位 3800.5"))
    }
}
