package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndicatorSeriesTest {

    @Test fun emptyInputReturnsEmptySeries() {
        val macd = computeMACD(emptyList())
        assertTrue(macd.dif.isEmpty() && macd.dea.isEmpty() && macd.hist.isEmpty())
        val kdj = computeKDJ(emptyList(), emptyList(), emptyList())
        assertTrue(kdj.k.isEmpty() && kdj.d.isEmpty() && kdj.j.isEmpty())
    }

    @Test fun constantPriceMakesMacdZero() {
        val closes = List(40) { 10.0 }
        val macd = computeMACD(closes)
        assertEquals(closes.size, macd.dif.size)
        assertEquals(closes.size, macd.dea.size)
        assertEquals(closes.size, macd.hist.size)

        assertEquals(0.0, macd.dif.last(), 1e-9)
        assertEquals(0.0, macd.dea.last(), 1e-9)
        assertEquals(0.0, macd.hist.last(), 1e-9)
    }

    @Test fun constantPriceKeepsKdjAtFifty() {
        val closes = List(30) { 10.0 }
        val kdj = computeKDJ(closes, closes, closes)
        assertEquals(closes.size, kdj.k.size)

        assertEquals(50.0, kdj.k.last(), 1e-9)
        assertEquals(50.0, kdj.d.last(), 1e-9)
        assertEquals(50.0, kdj.j.last(), 1e-9)
    }

    @Test fun risingPriceGivesPositiveDifAndFiniteHist() {
        val closes = (1..30).map { it.toDouble() }
        val macd = computeMACD(closes)

        assertTrue(macd.dif.last() > 0.0, "expected positive DIF, got ${macd.dif.last()}")
        assertTrue(macd.hist.all { it.isFinite() })
        assertTrue(macd.dea.all { it.isFinite() })
    }

    @Test fun risingPricePushesKdjHigh() {
        val closes = (1..30).map { it.toDouble() }
        val kdj = computeKDJ(closes, closes, closes)

        assertTrue(kdj.k.last() > kdj.k.first(), "K should rise: ${kdj.k.first()} -> ${kdj.k.last()}")
        assertTrue(kdj.k.last() > 50.0)
        assertTrue(kdj.k.all { it.isFinite() } && kdj.d.all { it.isFinite() } && kdj.j.all { it.isFinite() })
    }

    @Test fun highLowWindowDrivesRsvBetweenExtremes() {

        val highs = List(15) { 12.0 }
        val lows = List(15) { 8.0 }
        val closes = List(15) { 10.0 }
        val kdj = computeKDJ(highs, lows, closes)
        assertEquals(50.0, kdj.k.last(), 1e-9)
        assertEquals(50.0, kdj.d.last(), 1e-9)
    }

    @Test fun emptyInputReturnsEmptyRsi() {
        val rsi = computeRSI(emptyList())
        assertTrue(rsi.rsi6.isEmpty() && rsi.rsi12.isEmpty() && rsi.rsi24.isEmpty())
    }

    @Test fun constantPriceKeepsRsiAtFifty() {
        val closes = List(30) { 10.0 }
        val rsi = computeRSI(closes)
        assertEquals(closes.size, rsi.rsi6.size)

        assertEquals(50.0, rsi.rsi6.last(), 1e-9)
        assertEquals(50.0, rsi.rsi12.last(), 1e-9)
        assertEquals(50.0, rsi.rsi24.last(), 1e-9)
    }

    @Test fun risingPricePushesRsiToHundred() {
        val closes = (1..30).map { it.toDouble() }
        val rsi = computeRSI(closes)

        assertEquals(100.0, rsi.rsi6.last(), 1e-9)
        assertTrue(rsi.rsi6.all { it in 0.0..100.0 })
        assertTrue(rsi.rsi12.all { it.isFinite() } && rsi.rsi24.all { it.isFinite() })
    }

    @Test fun fallingPricePushesRsiToZero() {
        val closes = (1..30).map { (31 - it).toDouble() }
        val rsi = computeRSI(closes)

        assertEquals(0.0, rsi.rsi6.last(), 1e-9)
        assertTrue(rsi.rsi6.all { it in 0.0..100.0 })
    }

    @Test fun tooFewBarsGiveNoTrendlines() {
        val t = computeTrendlines(listOf(1.0, 2.0), listOf(1.0, 2.0), window = 2)
        assertNull(t.support)
        assertNull(t.resistance)
    }

    @Test fun descendingSwingHighsGiveFallingResistance() {
        val highs = listOf(1.0, 5.0, 2.0, 4.0, 1.0, 3.0, 0.5)
        val t = computeTrendlines(highs, highs, window = 1)
        val r = t.resistance!!

        assertTrue(r.x2 > r.x1)
        assertTrue(r.y2 < r.y1, "resistance should fall: ${r.y1} -> ${r.y2}")
    }

    @Test fun ascendingSwingLowsGiveRisingSupport() {
        val lows = listOf(9.0, 1.0, 8.0, 2.0, 9.0, 3.0, 9.0)
        val t = computeTrendlines(lows, lows, window = 1)
        val s = t.support!!

        assertTrue(s.x2 > s.x1)
        assertTrue(s.y2 > s.y1, "support should rise: ${s.y1} -> ${s.y2}")
    }

    @Test fun trendlineValueAtExtrapolatesLinearly() {
        val line = TrendLine(0, 10.0, 10, 20.0)
        assertEquals(25.0, line.valueAt(15), 1e-9)
        assertEquals(10.0, line.valueAt(0), 1e-9)
    }
}
