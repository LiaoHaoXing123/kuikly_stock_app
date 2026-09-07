package com.kuikly.stock.data

import kotlin.test.Test
import kotlin.test.assertEquals

class CompareCardsFormattingTest {
    @Test
    fun keyLevelShowsSupportBeforeResistance() {
        assertEquals("支 10.20 / 压 12.80", formatComparisonKeyLevel(10.2, 12.8))
    }

    @Test
    fun missingLevelDoesNotInventANumber() {
        assertEquals("-", formatComparisonKeyLevel(null, null))
    }
}
