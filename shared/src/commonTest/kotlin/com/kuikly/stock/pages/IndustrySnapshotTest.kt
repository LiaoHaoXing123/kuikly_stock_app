package com.kuikly.stock.pages

import com.kuikly.stock.data.IndustryMember
import com.kuikly.stock.data.IndustrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndustrySnapshotTest {
    @Test fun compareOnlySameDayValidQuotes() {
        val snapshot = IndustrySnapshot("银行", listOf(
            IndustryMember(StockListItem("a", price = 10.0, changePercent = 4.0), "2026-09-09"),
            IndustryMember(StockListItem("b", price = 11.0, changePercent = -2.0), "2026-09-09"),
            IndustryMember(StockListItem("stale", price = 12.0, changePercent = 20.0), "2026-09-08"),
            IndustryMember(StockListItem("missing", price = null, changePercent = 0.0), "2026-09-09")
        ))
        assertEquals(2, snapshot.current.size)
        assertEquals(1.0, snapshot.average)
        assertEquals(3.0, snapshot.relative("a"))
        assertNull(snapshot.relative("stale"))
    }

    @Test fun missingDataDoesNotBecomeZeroStrength() {
        assertNull(IndustrySnapshot("银行", emptyList()).average)
    }

    @Test fun fundWindowSelectsMostRecentTradingDaysRegardlessOfInputOrder() {
        fun flow(day: String) = FundFlowItem(day, 1.0, 1.0, null, null, null, null)
        val rows = listOf(flow("2026-09-09"), flow("2026-09-07"), flow("2026-09-08"))
        assertEquals(listOf("2026-09-08", "2026-09-09"), fundWindow(rows, 2).map { it.tradeDate })
        assertEquals(3, fundWindow(rows, 10).size)
    }
}
