package com.kuikly.stock.data

import com.kuikly.stock.pages.AIAnalysisData
import com.kuikly.stock.pages.KLineDataItem
import kotlin.test.*

class AiReviewEngineTest {

    private fun code(i: Int): String = "2025-01-" + (i + 1).toString().padStart(2, '0')

    private fun kline(vararg closes: Double): List<KLineDataItem> =
        closes.mapIndexed { i, c ->
            KLineDataItem(
                code = "000001", tradeDate = code(i),
                open = c * 0.99, close = c, high = c * 1.02, low = c * 0.98,
                volume = 1000.0, amount = null,
            )
        }

    private fun snapshot(
        dataDate: String,
        bias: String,
        support: Double? = null,
        resistance: Double? = null,
        target: Double? = null,
        stop: Double? = null,
        generatedAt: Long = 1000L,
    ) = AnalysisSnapshot(
        id = "stock:000001:$generatedAt", kind = "stock",
        result = AIAnalysisData(
            code = "000001", name = "测试", analysis = emptyMap(), cards = emptyList(),
            generatedAt = generatedAt, dataDate = dataDate,
            verdict = AIVerdict(
                bias = bias, oneLiner = "", confidence = "高", horizon = "短线",
                supportValue = support, resistanceValue = resistance,
                targetValue = target, stopLossValue = stop,
            ),
        ),
    )

    @Test
    fun bullishCallWithLevelsIsEvaluatedAgainstFollowingBars() {
        val bars = kline(10.0, 10.3, 10.6, 11.0, 11.6)
        val record = snapshot(dataDate = code(0), bias = "偏多", support = 9.0, target = 11.5, stop = 9.5)
        val summary = AiReviewEngine.review(listOf(record), bars)
        val entry = summary.entries.single()
        assertEquals("偏多", entry.bias)
        assertEquals(4, entry.elapsedDays)
        assertEquals(10.0, entry.basePrice!!, 1e-9)
        assertEquals(16.0, entry.returnPct!!, 1e-9) // (11.6-10)/10
        assertEquals(true, entry.directionHit)
        assertEquals(true, entry.supportHeld)
        assertEquals(true, entry.targetReached)
        assertEquals(false, entry.stopHit)
        assertEquals(false, entry.expired) // 4 < 10
        assertEquals("进行中", entry.status)
        assertEquals(1, summary.evaluatedCount)
        assertEquals(1, summary.directionHitCount)
        assertEquals(100, summary.hitRatePct)
    }

    @Test
    fun bearishCallAgainstRisingMarketCountsAsMiss() {
        val bars = kline(10.0, 10.3, 10.6, 11.0, 11.6)
        val record = snapshot(dataDate = code(0), bias = "偏空")
        val summary = AiReviewEngine.review(listOf(record), bars)
        assertEquals(false, summary.entries.single().directionHit)
        assertEquals(0, summary.directionHitCount)
        assertEquals(0, summary.hitRatePct)
    }

    @Test
    fun neutralCallInsideBandCountsAsHit() {
        val bars = kline(10.0, 10.2, 9.9)
        val record = snapshot(dataDate = code(0), bias = "中性")
        val summary = AiReviewEngine.review(listOf(record), bars)
        assertEquals(true, summary.entries.single().directionHit)
    }

    @Test
    fun analysisWithoutFollowingBarsIsPending() {
        val bars = kline(10.0, 10.3)
        val record = snapshot(dataDate = code(1), bias = "偏多")
        val entry = AiReviewEngine.review(listOf(record), bars).entries.single()
        assertNull(entry.directionHit)
        assertNull(entry.returnPct)
        assertEquals("待验证", entry.status)
        assertEquals(0, AiReviewEngine.review(listOf(record), bars).evaluatedCount)
    }

    @Test
    fun blankDataDateOrEmptyKlineCannotBeReviewed() {
        val bars = kline(10.0, 10.3)
        assertEquals("无法复盘", AiReviewEngine.review(listOf(snapshot("", "偏多")), bars).entries.single().status)
        assertTrue(AiReviewEngine.review(listOf(snapshot(code(0), "偏多")), emptyList()).entries.isEmpty())
    }

    @Test
    fun verdictOfFallsBackToLegacySynthesisWhenNotPersisted() {
        val legacy = AIAnalysisData(
            code = "000001", name = "测试",
            analysis = mapOf("summary" to "突破走强，建议买入", "trend" to "多头排列"),
            cards = emptyList(),
        )
        val verdict = AiReviewEngine.verdictOf(legacy)
        assertNotNull(verdict)
        assertEquals("偏多", verdict.bias)
        val v2 = legacy.copy(verdict = AIVerdict("偏空", "直接给结论"))
        assertEquals("偏空", AiReviewEngine.verdictOf(v2)?.bias)
    }
}
