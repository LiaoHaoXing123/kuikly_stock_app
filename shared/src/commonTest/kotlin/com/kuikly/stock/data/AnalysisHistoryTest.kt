package com.kuikly.stock.data

import com.kuikly.stock.pages.AIAnalysisData
import kotlin.test.*

class AnalysisHistoryTest {
    private val result = AIAnalysisData("000001", "平安银行", mapOf("summary" to "观察"), listOf(
        mapOf("type" to "signal_card", "signals" to listOf("放量", "突破"), "support_value" to 10.5)
    ))

    @Test fun roundTripRestoresNestedCardsAndSeparatesStockFromIndex() {
        val memory = mutableMapOf<String, String>()
        val store = AnalysisHistoryStore({ memory[it] }, { key, value -> memory[key] = value })
        assertTrue(store.save("stock", result))
        val restored = AnalysisHistoryStore({ memory[it] }, { key, value -> memory[key] = value })
        assertEquals(listOf("放量", "突破"), restored.list("stock", "000001").single().result.cards[0]["signals"])
        assertEquals(10.5, restored.list("stock", "000001").single().result.cards[0]["support_value"])
        assertTrue(restored.list("index", "000001").isEmpty())
    }

    @Test fun historyIsBoundedAndDeleteDoesNotRemoveOtherSymbols() {
        val memory = mutableMapOf<String, String>()
        val store = AnalysisHistoryStore({ memory[it] }, { key, value -> memory[key] = value })
        for (i in 1..25) assertTrue(store.save("stock", result.copy(generatedAt = i.toLong())))
        assertEquals(20, store.list("stock", result.code).size)
        assertEquals(25L, store.list("stock", result.code).first().result.generatedAt)
        assertTrue(store.save("index", result))
        val record = store.list("stock", result.code).first()
        assertTrue(store.delete(record.id))
        assertEquals(19, store.list("stock", result.code).size)
        assertEquals(1, store.list("index", result.code).size)
    }

    @Test fun corruptStorageIsRecoverableAndFailedWritesAreReported() {
        var raw = "broken json"
        val store = AnalysisHistoryStore({ raw }, { _, value -> raw = value })
        assertTrue(store.list("stock", result.code).isEmpty())
        assertTrue(store.save("stock", result))
        assertFalse(AnalysisHistoryStore({ null }, { _, _ -> }).save("stock", result))
    }

    @Test fun failedDeleteReadbackMustNotReportSuccess() {
        var raw = "[]"
        val setup = AnalysisHistoryStore({ raw }, { _, value -> raw = value })
        assertTrue(setup.save("stock", result))
        val id = setup.list("stock", result.code).single().id
        var failedRead = false
        val failing = AnalysisHistoryStore({ if (failedRead) null else raw }, { _, _ -> failedRead = true })
        assertFalse(failing.delete(id))
    }

    @Test fun verdictRoundTripsAndOldRecordsWithoutVerdictDecodeToNull() {
        val memory = mutableMapOf<String, String>()
        val withVerdict = result.copy(verdict = AIVerdict("偏多", "看涨", confidence = "高", supportValue = 10.5))
        val store = AnalysisHistoryStore({ memory[it] }, { key, value -> memory[key] = value })
        assertTrue(store.save("stock", withVerdict))
        val restored = AnalysisHistoryStore({ memory[it] }, { key, value -> memory[key] = value })
            .list("stock", "000001").single().result
        assertEquals("偏多", restored.verdict?.bias)
        assertEquals(10.5, restored.verdict?.supportValue)

        // verdict 持久化之前写入的历史 payload（无 verdict 字段）必须仍可解码
        val legacyJson = """[{"id":"stock:000001:1","kind":"stock","code":"000001","name":"平安银行",
            "source":"DeepSeek","generatedAt":1,"dataDate":"2025-01-01",
            "analysis":{"summary":"观察"},"cards":[]}]"""
        val legacyStore = AnalysisHistoryStore({ legacyJson }, { _, _ -> })
        assertNull(legacyStore.list("stock", "000001").single().result.verdict)
    }
}
