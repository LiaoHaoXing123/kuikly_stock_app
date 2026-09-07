package com.kuikly.stock.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortfolioRiskCalculatorTest {

    @Test
    fun calculatesPnlAndConcentration() {
        val result = PortfolioRiskCalculator.calculate(
            listOf(
                HoldingSnapshot("600519", "贵州茅台", "白酒", 10.0, 1000.0, 1200.0),
                HoldingSnapshot("000858", "五粮液", "白酒", 20.0, 50.0, 40.0),
            )
        )

        assertEquals(11000.0, result.totalCost, 0.001)
        assertEquals(12800.0, result.marketValue, 0.001)
        assertEquals(1800.0, result.pnl, 0.001)
        assertEquals(1800.0 / 11000.0, result.pnlRate, 0.0001)
        assertEquals(0.9375, result.maxStockWeight, 0.0001)
        assertEquals(1.0, result.maxIndustryWeight, 0.0001)
        assertTrue(result.riskMessages.any { it.contains("单股集中度") })
        assertTrue(result.riskMessages.any { it.contains("行业集中度") })
    }

    @Test
    fun emptyPortfolioHasNoFakeRisk() {
        assertEquals(PortfolioRiskSummary.empty(), PortfolioRiskCalculator.calculate(emptyList()))
    }

    @Test
    fun missingPriceIsCountedButExcludedFromTotals() {
        val result = PortfolioRiskCalculator.calculate(
            listOf(HoldingSnapshot("000001", "平安银行", "银行", 100.0, 10.0, null))
        )

        assertEquals(0, result.pricedCount)
        assertEquals(1, result.unavailableCount)
        assertEquals(0.0, result.marketValue)
    }
}
