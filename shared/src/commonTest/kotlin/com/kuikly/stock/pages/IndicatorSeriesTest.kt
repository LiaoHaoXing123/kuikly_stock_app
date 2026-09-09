package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals
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
        // EMA(常数)=常数 → dif=0、dea=0、hist=0
        assertEquals(0.0, macd.dif.last(), 1e-9)
        assertEquals(0.0, macd.dea.last(), 1e-9)
        assertEquals(0.0, macd.hist.last(), 1e-9)
    }

    @Test fun constantPriceKeepsKdjAtFifty() {
        val closes = List(30) { 10.0 }
        val kdj = computeKDJ(closes, closes, closes)
        assertEquals(closes.size, kdj.k.size)
        // 高=低 → RSV=50 → K=D=50、J=50
        assertEquals(50.0, kdj.k.last(), 1e-9)
        assertEquals(50.0, kdj.d.last(), 1e-9)
        assertEquals(50.0, kdj.j.last(), 1e-9)
    }

    @Test fun risingPriceGivesPositiveDifAndFiniteHist() {
        val closes = (1..30).map { it.toDouble() }
        val macd = computeMACD(closes)
        // 上升趋势：快线高于慢线 → DIF>0
        assertTrue(macd.dif.last() > 0.0, "expected positive DIF, got ${macd.dif.last()}")
        assertTrue(macd.hist.all { it.isFinite() })
        assertTrue(macd.dea.all { it.isFinite() })
    }

    @Test fun risingPricePushesKdjHigh() {
        val closes = (1..30).map { it.toDouble() }
        val kdj = computeKDJ(closes, closes, closes)
        // 收盘持续接近窗口高点 → K 上行、显著高于起点与 50
        assertTrue(kdj.k.last() > kdj.k.first(), "K should rise: ${kdj.k.first()} -> ${kdj.k.last()}")
        assertTrue(kdj.k.last() > 50.0)
        assertTrue(kdj.k.all { it.isFinite() } && kdj.d.all { it.isFinite() } && kdj.j.all { it.isFinite() })
    }

    @Test fun highLowWindowDrivesRsvBetweenExtremes() {
        // close 落在窗口高低之间时 K 应处于 (0,100) 且随收盘位置合理变化
        val highs = List(15) { 12.0 }
        val lows = List(15) { 8.0 }
        val closes = List(15) { 10.0 } // 恰在高低中点 → RSV=50
        val kdj = computeKDJ(highs, lows, closes)
        assertEquals(50.0, kdj.k.last(), 1e-9)
        assertEquals(50.0, kdj.d.last(), 1e-9)
    }
}
