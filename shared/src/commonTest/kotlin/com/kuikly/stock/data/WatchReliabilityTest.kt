package com.kuikly.stock.data

import kotlin.test.*

class WatchReliabilityTest {
    private fun repository(values: MutableMap<String, String> = mutableMapOf()) = WatchRepository({ values[it] }, { key, value -> values[key] = value })

    @Test fun alertForUnwatchedStockRemainsManageableInWatchlist() {
        val store = repository()
        assertTrue(store.upsertAlert(PriceAlertRule("600000", "浦发", 0, 12.0)))
        assertTrue(store.isWatched("600000"))
        store.updateHolding(WatchHolding("600000", "浦发", 100.0, 9.0))
        assertTrue(store.upsertAlert(PriceAlertRule("600000", "浦发", 1, 8.0)))
        assertEquals(100.0, store.find("600000")?.shares)
    }

    @Test fun rulesForSameStockCoexistAndExactRuleMutationsPreserveOthers() {
        val store = repository()
        val high = PriceAlertRule("600000", "浦发", 0, 12.0)
        val low = high.copy(type = 1, threshold = 8.0)
        val higher = high.copy(threshold = 15.0)
        listOf(high, low, higher).forEach { assertTrue(store.upsertAlert(it)) }
        assertEquals(3, store.alerts().size)
        store.upsertAlert(high.copy(enabled = false))
        assertEquals(3, store.alerts().size)
        assertTrue(store.alerts().contains(low))
        store.removeAlert(high)
        assertEquals(listOf(low, higher), store.alerts())
    }
    @Test fun cancelWatchRemovesOnlyThatStocksRules() {
        val store = repository()
        store.updateHolding(WatchHolding("600000", "浦发"))
        store.upsertAlert(PriceAlertRule("600000", "浦发", 0, 12.0))
        val other = PriceAlertRule("600519", "茅台", 1, 1000.0)
        store.upsertAlert(other)
        assertFalse(store.toggle("600000", "浦发"))
        assertEquals(listOf(other), store.alerts())
    }
    @Test fun corruptStorageIsEmptyAndFailedWriteIsReported() {
        assertTrue(repository(mutableMapOf("alert_v1" to "broken")).alerts().isEmpty())
        val store = WatchRepository({ null }, { _, _ -> })
        assertFalse(store.upsertAlert(PriceAlertRule("600000", "浦发", 0, 12.0)))
    }
    @Test fun identitiesDoNotRoundThresholds() {
        val a = PriceAlertRule("600000", "浦发", 0, 12.001)
        assertNotEquals(a.identity, a.copy(threshold = 12.002).identity)
    }
    @Test fun legacyAlertsRemainReadable() {
        val store = repository(mutableMapOf("alert_v1" to """{"items":[{"code":"600000","name":"浦发","type":1,"threshold":8,"enabled":true}]}"""))
        assertEquals(8.0, store.alerts().single().threshold)
        store.upsertAlert(PriceAlertRule("600000", "浦发", 0, 12.0))
        assertEquals(2, store.alerts().size)
    }
    @Test fun clearingHoldingPreservesWatchAndAlerts() {
        val store = repository()
        store.updateHolding(WatchHolding("600000", "浦发", 100.0, 10.0))
        store.upsertAlert(PriceAlertRule("600000", "浦发", 0, 12.0))
        store.clearHolding("600000")
        assertEquals(WatchHolding("600000", "浦发"), store.find("600000"))
        assertEquals(1, store.alerts().size)
    }
    @Test fun underflowAndNegativeZeroCannotClearHolding() {
        for (bad in listOf("1e-999", "-1e-999", "-0", "0." + "0".repeat(400) + "1")) {
            assertNull(HoldingInput.parse("600000", bad, "10", allowClear = true), bad)
            assertNull(HoldingInput.parse("600000", "100", bad, allowClear = true), bad)
        }
        assertNotNull(HoldingInput.parse("600000", "0.00", "10", allowClear = true))
    }
    @Test fun holdingCostMustNotOverflow() {
        val huge = "1" + "0".repeat(200)
        assertNull(HoldingInput.parse("600000", huge, huge))
    }
    @Test fun invalidHoldingInputCannotClearAPosition() {
        for (bad in listOf("", "abc", "NaN", "Infinity", "-1", "1e999")) {
            assertNull(HoldingInput.parse("600000", bad, "10", allowClear = true), bad)
            assertNull(HoldingInput.parse("600000", "100", bad, allowClear = true), bad)
        }
        assertNull(HoldingInput.parse("ABC123", "100", "10"))
        assertNull(HoldingInput.parse("600000", "0", "10"))
        assertNull(HoldingInput.parse("600000", "100", "0"))
        assertNotNull(HoldingInput.parse("600000", "0", "10", allowClear = true))
        assertNotNull(HoldingInput.parse("600000", "100", "10"))
    }
}
