package com.kuikly.stock.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeDashboardServiceTest {

    @Test
    fun negativeBreadthProducesCautiousBrief() {
        val brief = buildDashboardBrief(
            total = 5909,
            up = 2444,
            down = 2914,
            flat = 551,
            watchSignals = 1,
            alertCount = 2,
            dataDate = "09-07",
        )

        assertEquals("先看风险，再找机会", brief.headline)
        assertTrue(brief.summary.contains("涨跌家数偏弱"))
        assertEquals("市场偏弱", brief.marketLabel)
        assertEquals("09-07", brief.dataDate)
    }

    @Test
    fun positiveBreadthProducesConstructiveBrief() {
        val brief = buildDashboardBrief(5000, 3200, 1500, 300, 0, 0, "09-07")

        assertEquals("顺势观察，精选强势", brief.headline)
        assertEquals("市场偏强", brief.marketLabel)
    }

    @Test
    fun focusItemsPutTriggeredAlertsFirst() {
        val items = buildFocusItems(
            alertMessages = listOf("贵州茅台触及提醒价"),
            watchSignals = listOf("宁德时代站上 MA5"),
            limit = 2,
        )

        assertEquals("提醒触发", items.first().tag)
        assertEquals(2, items.size)
    }

    @Test
    fun emptyFocusHasHelpfulState() {
        val items = buildFocusItems(emptyList(), emptyList(), 2)

        assertEquals(1, items.size)
        assertTrue(items.first().title.contains("暂无"))
    }
}
