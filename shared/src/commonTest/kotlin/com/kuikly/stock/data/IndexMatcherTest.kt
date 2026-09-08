package com.kuikly.stock.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexMatcherTest {
    private val candidates = listOf(
        IndexCandidate("000001", "上证指数"),
        IndexCandidate("399001", "深证成指"),
        IndexCandidate("399006", "创业板指"),
        IndexCandidate("000300", "沪深300"),
        IndexCandidate("000688", "科创50"),
    )

    @Test fun fullNameHits() {
        val r = matchIndexCandidates("上证指数今天怎么样", candidates)
        assertEquals(listOf("000001"), r.map { it.code })
        assertTrue(r.single().isIndex)
    }

    @Test fun aliasHits() {
        assertEquals("000001", matchIndexCandidates("今天大盘怎么样", candidates).single().code)
        assertEquals("399006", matchIndexCandidates("创业板走强", candidates).single().code)
        assertEquals("399001", matchIndexCandidates("深证涨了多少", candidates).single().code)
    }

    @Test fun shortNameWithContextHits() {
        // "上证"本身是语境词，去后缀简称可命中
        assertEquals("000001", matchIndexCandidates("上证涨了", candidates).single().code)
    }

    @Test fun shortNameWithoutContextDoesNotHit() {
        // "银行"无指数语境时不得命中"银行指数"
        val cands = candidates + IndexCandidate("399999", "银行指数")
        assertTrue(matchIndexCandidates("银行股怎么样", cands).isEmpty())
    }

    @Test fun codeWithoutContextMisses() {
        // 无指数语境时 000001 默认走个股，指数侧不命中
        assertTrue(matchIndexCandidates("000001怎么样", candidates).isEmpty())
    }

    @Test fun codeWithContextHits() {
        val r = matchIndexCandidates("指数000001点位多少", candidates)
        assertEquals(listOf("000001"), r.map { it.code })
    }

    @Test fun blankAndEmptyAreSafe() {
        assertTrue(matchIndexCandidates("", candidates).isEmpty())
        assertTrue(matchIndexCandidates("上证指数", emptyList()).isEmpty())
    }

    @Test fun capsAtThree() {
        val r = matchIndexCandidates("上证指数深证成指创业板指沪深300", candidates)
        assertEquals(3, r.size)
    }
}
