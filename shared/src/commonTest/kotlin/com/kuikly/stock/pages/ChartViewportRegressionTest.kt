package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals

class ChartViewportRegressionTest {
    private fun mapper(count: Int) = ChartCoordinateMapper(0, count, 10.0, 20.0, 300f, 0f, 200f)

    @Test fun pinchHandlesShortMonthlyHistory() {
        assertEquals(0 to 3, mapper(3).zoomAt(150f, 2f, 3))
    }

    @Test fun pinchHandlesEmptyHistory() {
        assertEquals(0 to 0, mapper(0).zoomAt(150f, 2f, 0))
    }

    @Test fun invalidScaleLeavesViewportUnchanged() {
        assertEquals(0 to 30, mapper(30).zoomAt(150f, Float.NaN, 60))
    }

    @Test fun analysisDateUsesDataDateAndNormalizesFormatting() {
        assertEquals("分析基于 2026-09-09", analysisDateLabel("20260909", "2026-09-09"))
        assertEquals("分析基于 2026-09-08 · 图表截至 2026-09-09，日期不同", analysisDateLabel("2026-09-08", "20260909"))
        assertEquals("分析行情日期未提供，请核对数据", analysisDateLabel("", "20260909"))
    }
}
