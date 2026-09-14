package com.kuikly.stock.data

import com.kuikly.stock.pages.KLineDataItem
import kotlin.test.*

/** Repeated user edits, reloads and removals must not accumulate or cross stock boundaries. */
class PortfolioOperationStressTest {
    @Test fun repeatedEditsReloadsAndClearsKeepOtherStockAndAlertsIntact() {
        val disk = mutableMapOf<String, String>()
        fun reopen() = WatchRepository({ disk[it] }, { key, value -> disk[key] = value })
        val a = WatchHolding("000001", "A", 100.0, 10.0, "2026-09-07", "2026-09-09")
        val b = WatchHolding("600519", "B", 200.0, 20.0, "2026-09-08")
        val rule = PriceAlertRule(b.code, b.name, 0, 21.0)
        val histories = listOf(a, b).associate { h -> h.code to (7..10).map { day ->
            val close = if (h.code == a.code) day + 3.0 else day + 13.0
            KLineDataItem(h.code, "2026-09-${day.toString().padStart(2, '0')}", close, close, close, close, 100.0, null)
        } }
        reopen().updateHolding(b)
        repeat(100) { iteration ->
            val store = reopen()
            val edited = a.copy(shares = (iteration + 1) * 100.0)
            repeat(3) { store.updateHolding(edited); assertTrue(store.upsertAlert(rule)) }
            val loaded = reopen()
            assertEquals(2, loaded.list().size)
            assertEquals(b, loaded.find(b.code))
            assertEquals(listOf(rule), loaded.alerts())
            val days = datedPortfolioDays(loaded.list(), histories)
            // A earns 0 on entry, then 1/share on each of its two remaining days.
            // B earns 1/share on entry and on each of its two remaining days.
            assertEquals(edited.shares * 2 + 600.0, days.sumOf { it.dayPnl }, 1e-8)
            assertEquals(listOf(b.code), days.last().holdings.map { it.code })
            loaded.clearHolding(a.code)
            assertEquals(600.0, datedPortfolioDays(reopen().list(), histories).sumOf { it.dayPnl }, 1e-8)
            loaded.remove(a.code)
            assertEquals(listOf(b), reopen().list())
            assertEquals(listOf(rule), reopen().alerts())
        }
    }
}
