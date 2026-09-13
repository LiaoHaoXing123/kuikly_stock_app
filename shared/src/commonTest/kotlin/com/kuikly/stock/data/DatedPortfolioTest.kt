package com.kuikly.stock.data

import com.kuikly.stock.pages.KLineDataItem
import kotlin.test.*

class DatedPortfolioTest {
    private fun bars(code: String, vararg closes: Double) = closes.mapIndexed { i, v ->
        KLineDataItem(code, "2026-09-${(i + 7).toString().padStart(2, '0')}", v, v, v, v, 100.0, null)
    }
    @Test fun differentEntryDatesUseCostOnceThenSumDailyChanges() {
        val positions = listOf(WatchHolding("a", "A", 100.0, 9.0, "2026-09-08"),
            WatchHolding("b", "B", 200.0, 20.0, "2026-09-09"))
        val days = datedPortfolioDays(positions, mapOf("a" to bars("a", 10.0, 11.0, 12.0, 11.0),
            "b" to bars("b", 20.0, 20.0, 21.0, 19.0)))
        assertEquals(listOf("2026-09-08", "2026-09-09", "2026-09-10"), days.map { it.date })
        assertEquals(listOf(200.0, 300.0, -500.0), days.map { it.dayPnl })
        assertEquals(listOf(1, 2, 2), days.map { it.holdings.size })
        assertEquals(0.0, days.sumOf { it.dayPnl })
    }
    @Test fun missingQuoteDoesNotBecomePartialPortfolioTotal() {
        val positions = listOf(WatchHolding("a", "A", 100.0, 9.0, "2026-09-07"),
            WatchHolding("b", "B", 100.0, 9.0, "2026-09-08"))
        val days = datedPortfolioDays(positions, mapOf("a" to bars("a", 10.0, 11.0)))
        assertEquals(listOf("2026-09-07"), days.map { it.date })
        assertTrue(datedPortfolioDays(listOf(positions[0].copy(startDate = "")), mapOf("a" to bars("a", 10.0))).isEmpty())
    }
    @Test fun recomputeUsesChangedDatesAndQuantities() {
        val history = mapOf("a" to bars("a", 10.0, 11.0, 12.0))
        val p = WatchHolding("a", "A", 100.0, 10.0, "2026-09-07")
        assertEquals(3, datedPortfolioDays(listOf(p), history).size)
        val changed = datedPortfolioDays(listOf(p.copy(shares = 200.0, startDate = "2026-09-09")), history)
        assertEquals(1, changed.size)
        assertEquals(400.0, changed.single().dayPnl)
        assertTrue(datedPortfolioDays(emptyList(), history).isEmpty())
    }
    @Test fun startDateSurvivesStorageAndLegacyDateStaysUnknown() {
        val memory = mutableMapOf<String, String>()
        val store = WatchRepository({ memory[it] }, { k,v -> memory[k] = v })
        val p = WatchHolding("a", "A", 100.0, 9.0, "2026-09-07")
        store.updateHolding(p)
        assertEquals(p, store.find("a"))
        store.clearHolding("a")
        assertEquals("", store.find("a")!!.startDate)
        memory["watch_v1"] = """{"items":[{"code":"b","name":"B","shares":100,"cost":9}]}"""
        assertEquals("", store.find("b")!!.startDate)
    }
    @Test fun weekendEntryUsesCostAtNextTradingDayWithoutFakeLimitEvent() {
        val friday = bars("a", 10.0).single().copy(tradeDate = "2026-09-04")
        val monday = friday.copy(tradeDate = "2026-09-07", close = 10.1)
        val p = WatchHolding("a", "A", 100.0, 8.0, "2026-09-05")
        val day = datedPortfolioDays(listOf(p), mapOf("a" to listOf(friday, monday))).single()
        assertEquals(210.0, day.dayPnl, 1e-8)
        assertTrue(day.events.isEmpty())
    }
}
