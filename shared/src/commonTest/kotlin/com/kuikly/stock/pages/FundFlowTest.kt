package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FundFlowTest {

    @Test fun moneyFormatUsesWanAndYi() {
        assertEquals("1.23亿", fmtMoney(1.23e8))
        assertEquals("-6.42亿", fmtMoney(-6.42e8))
        assertEquals("3456万", fmtMoney(3.456e7))
        assertEquals("-890万", fmtMoney(-8.9e6))
        assertEquals("890", fmtMoney(890.0))
        assertEquals("0", fmtMoney(0.0))
    }

    @Test fun moneyFormatBoundaries() {
        // 1 亿边界：9,999 万 -> 万单位，1 亿 -> 亿单位
        assertEquals("9999万", fmtMoney(9.999e7))
        assertEquals("1.00亿", fmtMoney(1.0e8))
        // 1 万边界
        assertEquals("9999", fmtMoney(9999.0))
        assertEquals("1万", fmtMoney(1.0004e4))
    }

    @Test fun fundFlowItemConstructs() {
        val f = FundFlowItem(
            tradeDate = "2026-09-09",
            mainNet = -641850339.1,
            mainRatio = -15.73,
            superNet = null, bigNet = null, midNet = null, smallNet = null,
        )
        assertEquals("2026-09-09", f.tradeDate)
        assertEquals(-641850339.1, f.mainNet)
        assertEquals(-15.73, f.mainRatio)
    }

    @Test fun stockDetailDataDefaultsFundFlowToNull() {
        // 旧构造（不含 fundFlow）必须仍可编译/可用 —— 不破坏 iOS/JS/测试
        val d = StockDetailData(info = null, realtime = null, kline = null)
        assertEquals(null, d.fundFlow)
    }

    @Test fun stockDetailDataCarriesFundFlow() {
        val flows = listOf(
            FundFlowItem("2026-09-09", -6.418e8, -15.73, null, null, null, null),
            FundFlowItem("2026-09-08", -2.989e8, -13.33, null, null, null, null),
        )
        val d = StockDetailData(info = null, realtime = null, kline = null, fundFlow = flows)
        assertEquals(2, d.fundFlow?.size)
        assertTrue(d.fundFlow!![0].mainNet < 0)
    }
}
