package com.kuikly.stock.data

import com.kuikly.stock.pages.*
import kotlin.test.*

class AnalysisTemplateTest {
    @Test fun templateUsesCapturedQuoteInsteadOfBundledAssets() {
        val detail = StockDetailData(
            StockInfoData("999999", "测试股票", null, null, null),
            RealtimeQuoteData("999999", "测试股票", 100.0, 1.0, 1.0, 99.0, 99.0, 101.0, 98.0, 1000.0, 100000.0, null, null),
            emptyList(),
        )
        val result = LocalDataService.mockAnalysis(detail)
        assertEquals("999999", result.code)
        val suggestion = result.cards.single { it["type"] == "suggestion_card" }
        assertEquals("105.00", suggestion["target_price"])
    }
}
