package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals

class DetailChartTest {
    private fun bar(date: String, open: Double = 10.0, close: Double = 12.0) =
        KLineDataItem("000001", date, open, close, 13.0, 9.0, 100.0, 1000.0)

    @Test fun shortHolidayWeekAggregatesRatherThanReturningDailyBars() {
        val result = aggregateToWeekly(listOf(bar("2026-09-07"), bar("2026-09-09", 12.0, 11.0)))
        assertEquals(1, result.size)
        assertEquals(10.0, result.single().open)
        assertEquals(11.0, result.single().close)
        assertEquals(200.0, result.single().volume)
    }

    @Test fun calendarWeeksRemainAlignedAcrossYearBoundary() {
        val result = aggregateToWeekly(listOf(bar("2025-12-31"), bar("2026-01-02"), bar("2026-01-05")))
        assertEquals(listOf("2026-01-02", "2026-01-05"), result.map { it.tradeDate })
    }

    @Test fun compactDatesAndUnsortedInputKeepChronologicalOpenClose() {
        val result = aggregateToWeekly(listOf(bar("20260911", 12.0, 14.0), bar("20260907", 10.0, 11.0)))
        assertEquals(1, result.size)
        assertEquals(10.0, result.single().open)
        assertEquals(14.0, result.single().close)
    }

    @Test fun monthlyGroupingKeepsYearAndMissingAmount() {
        val result = aggregateToMonthly(listOf(bar("20260102").copy(amount = null), bar("20251231").copy(amount = null)))
        assertEquals(listOf("20251231", "20260102"), result.map { it.tradeDate })
        assertEquals(null, result.first().amount)
    }
}
