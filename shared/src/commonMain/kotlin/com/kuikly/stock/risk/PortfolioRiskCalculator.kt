package com.kuikly.stock.risk

internal data class HoldingSnapshot(
    val code: String,
    val name: String,
    val industry: String,
    val shares: Double,
    val cost: Double,
    val currentPrice: Double?,
)

internal data class HoldingRiskLine(
    val code: String,
    val name: String,
    val industry: String,
    val shares: Double,
    val costValue: Double,
    val marketValue: Double,
    val pnl: Double,
    val pnlRate: Double,
    val weight: Double,
)

internal data class PortfolioRiskSummary(
    val totalCost: Double,
    val marketValue: Double,
    val pnl: Double,
    val pnlRate: Double,
    val maxStockWeight: Double,
    val maxIndustryWeight: Double,
    val pricedCount: Int,
    val unavailableCount: Int,
    val lines: List<HoldingRiskLine>,
    val riskMessages: List<String>,
) {
    companion object {
        fun empty() = PortfolioRiskSummary(
            totalCost = 0.0,
            marketValue = 0.0,
            pnl = 0.0,
            pnlRate = 0.0,
            maxStockWeight = 0.0,
            maxIndustryWeight = 0.0,
            pricedCount = 0,
            unavailableCount = 0,
            lines = emptyList(),
            riskMessages = emptyList(),
        )
    }
}

internal object PortfolioRiskCalculator {
    fun calculate(holdings: List<HoldingSnapshot>): PortfolioRiskSummary {
        if (holdings.isEmpty()) return PortfolioRiskSummary.empty()

        val priced = holdings.filter { holding ->
            holding.shares > 0.0 &&
                holding.shares.isFinite() &&
                holding.cost >= 0.0 &&
                holding.cost.isFinite() &&
                holding.currentPrice?.let { it > 0.0 && it.isFinite() } == true
        }
        val unavailableCount = holdings.size - priced.size
        val totalCost = priced.sumOf { it.shares * it.cost }
        val marketValue = priced.sumOf { it.shares * (it.currentPrice ?: 0.0) }
        val pnl = marketValue - totalCost
        val pnlRate = if (totalCost > 0.0) pnl / totalCost else 0.0

        val lines = priced.map { holding ->
            val costValue = holding.shares * holding.cost
            val value = holding.shares * (holding.currentPrice ?: 0.0)
            val linePnl = value - costValue
            HoldingRiskLine(
                code = holding.code,
                name = holding.name,
                industry = holding.industry.ifBlank { "未分类" },
                shares = holding.shares,
                costValue = costValue,
                marketValue = value,
                pnl = linePnl,
                pnlRate = if (costValue > 0.0) linePnl / costValue else 0.0,
                weight = if (marketValue > 0.0) value / marketValue else 0.0,
            )
        }.sortedByDescending { it.marketValue }

        val maxStockWeight = lines.maxOfOrNull { it.weight } ?: 0.0
        val maxIndustryWeight = if (marketValue > 0.0) {
            lines.groupBy { it.industry }
                .values
                .maxOfOrNull { group -> group.sumOf { it.marketValue } / marketValue } ?: 0.0
        } else {
            0.0
        }

        val messages = buildList {
            if (maxStockWeight >= 0.5) add("单股集中度较高，建议检查仓位上限")
            if (maxIndustryWeight >= 0.7) add("行业集中度较高，组合分散度不足")
            if (pnlRate <= -0.15) add("组合回撤已超过 15%，建议复核止损纪律")
            if (unavailableCount > 0) add("有 $unavailableCount 个持仓缺少有效行情，暂未计入总值")
        }

        return PortfolioRiskSummary(
            totalCost = totalCost,
            marketValue = marketValue,
            pnl = pnl,
            pnlRate = pnlRate,
            maxStockWeight = maxStockWeight,
            maxIndustryWeight = maxIndustryWeight,
            pricedCount = priced.size,
            unavailableCount = unavailableCount,
            lines = lines,
            riskMessages = messages,
        )
    }
}
