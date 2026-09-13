package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailChartTest {
    @Test fun localRefreshPreservesBackfilledHistoryButNewerDataWins() {
        val history = listOf(bar("2026-08-31"), bar("2026-09-01"), bar("2026-09-02"))
        assertEquals(history, retainLongerHistory(history, history.takeLast(1)))
        val newer = listOf(bar("2026-09-03"))
        assertEquals(newer, retainLongerHistory(history, newer))
        assertEquals(2, aggregateToMonthly(history).size)
        assertEquals(1, aggregateToWeekly(history).size)
    }
    @Test fun followupKeepsFullRangeStatisticsBeyondBoundedCandles() {
        val data = (1..20).map { bar("2026-09-${it.toString().padStart(2, '0')}") }
        val range = summarizeRange(data, 0, 19)!!
        val prompt = detailFollowupPrompt("stock", "000001", "平安银行", "D", data, 19, null,
            range, data.takeLast(12), "macd")
        assertTrue(prompt.contains("选中区间 2026-09-01 至 2026-09-20，共20根"))
        assertTrue(prompt.contains("累计成交量2000.0"))
        assertTrue(prompt.contains("视口：2026-09-09 至 2026-09-20，12根；副图：macd"))
        assertTrue(prompt.contains("本地历史快照，非实时"))
        assertEquals(6, prompt.lines().count { it.matches(Regex("2026-09-.* 开.*")) })
    }

    @Test fun emptyChartContextDoesNotInventDateOrRange() {
        val prompt = detailFollowupPrompt("index", "000001", "上证指数", "W", emptyList(), -1, null)
        assertTrue(prompt.contains("视口：无 至 无，0根"))
        assertTrue(prompt.contains("周K（自然周）"))
        assertTrue(!prompt.contains("本地计算："))
    }
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
