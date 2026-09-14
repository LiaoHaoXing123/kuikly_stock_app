package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlineViewportTest {
    @Test fun dailyOffsetIsClampedWhileSwitchingToWeeklyData() {
        assertEquals(24..53, klineViewport(54, 221, 30))
    }

    @Test fun monthlyDataAcceptsThePreviousDailyWindow() {
        val closes = List(13) { it.toDouble() }
        val window = klineViewport(closes.size, 221, 30)
        assertEquals(0..12, window)
        window.filter { it >= 4 }.forEach { index ->
            assertEquals(index - 2.0, closes.subList(index - 4, index + 1).average())
        }
    }

    @Test fun emptyAndUninitializedWindowsAreSafe() {
        assertTrue(klineViewport(0, 221, 30).isEmpty())
        assertTrue(klineViewport(54, 221, 0).isEmpty())
        assertEquals(0..25, klineViewport(54, -1, 26))
    }
}
