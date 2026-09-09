package com.kuikly.stock.pages

import kotlin.test.*

class ChartEvidenceTest {
    private fun bar(day: Int, close: Double = 10.0, volume: Double = 100.0) =
        KLineDataItem("000001", "2026-09-${day.toString().padStart(2, '0')}", 10.0, close, close + 1, 9.0, volume, null)

    @Test fun hitTestUsesActualVisibleBarsAndClampsEdges() {
        assertEquals(2, chartHitIndex(50f, 100f, 5))
        assertEquals(4, chartHitIndex(100f, 100f, 5))
        assertEquals(0, chartHitIndex(-2f, 100f, 5))
        assertEquals(-1, chartHitIndex(20f, 0f, 5))
        assertEquals(-1, chartHitIndex(Float.NaN, 100f, 5))
    }

    @Test fun volumeEvidenceUsesPreviousFiveBarsAndCarriesExactDate() {
        val data = (1..5).map { bar(it) } + bar(6, 12.0, 250.0)
        val evidence = chartEvidence(data).first { it.title.contains("放量") }
        assertEquals(5, evidence.index)
        assertTrue(evidence.detail.contains("2.50"))
        assertTrue(evidence.detail.contains("2026-09-06"))
    }

    @Test fun insufficientHistoryDoesNotInventVolumeSignal() {
        assertTrue(chartEvidence(listOf(bar(1))).none { it.title.contains("放量") })
    }

    @Test fun followupCarriesKindPeriodSelectionAndProvenance() {
        val prompt = detailFollowupPrompt("index", "000001", "上证指数", "W", listOf(bar(1), bar(2)), 1, null)
        assertTrue(prompt.contains("指数"))
        assertTrue(prompt.contains("周K"))
        assertTrue(prompt.contains("2026-09-02"))
        assertTrue(prompt.contains("尚未生成"))
    }

    @Test fun modelEvidenceOnlyLinksDatesActuallySentToModel() {
        val raw = listOf(
            mapOf("date" to "20260901", "reason" to "成交量变化"),
            mapOf("date" to "2030-01-01", "reason" to "不存在的行情"),
            mapOf("date" to "2026-09-01", "reason" to ""),
        )
        val result = validatedAnalysisEvidence(raw, listOf(bar(1)))
        assertEquals(1, result.size)
        assertEquals("2026-09-01", result.single()["date"])
        assertEquals("成交量变化", result.single()["reason"])
    }
}
