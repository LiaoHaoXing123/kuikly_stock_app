package com.kuikly.stock.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConclusionAlertFactoryTest {
    @Test
    fun supportCreatesPriceBelowAlert() {
        val rule = ConclusionAlertFactory.support("600519", "贵州茅台", 1305.0)
        assertEquals(1, rule.type)
        assertEquals(1305.0, rule.threshold)
    }

    @Test
    fun resistanceCreatesPriceAboveAlert() {
        val rule = ConclusionAlertFactory.resistance("600519", "贵州茅台", 1338.0)
        assertEquals(0, rule.type)
        assertEquals(1338.0, rule.threshold)
    }

    @Test
    fun rejectsInvalidStockOrPrice() {
        assertFailsWith<IllegalArgumentException> {
            ConclusionAlertFactory.support("", "贵州茅台", 1305.0)
        }
        assertFailsWith<IllegalArgumentException> {
            ConclusionAlertFactory.resistance("600519", "贵州茅台", Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            ConclusionAlertFactory.resistance("600519", "贵州茅台", 0.0)
        }
    }
}
