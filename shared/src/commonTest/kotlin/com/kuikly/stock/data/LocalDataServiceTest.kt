package com.kuikly.stock.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * stock_list.json 是数据管道产出、随包发布的资产，条目结构随管道演进会变。
 * 这里锁住解析的容错行为：单条畸形不应让整表加载失败。
 */
class LocalDataServiceTest {

    @Test
    fun parsesWellFormedRows() {
        val raw = """
            [
              {"code":"000001","name":"平安银行","price":11.74,"change_percent":-0.93,"change":-0.11,"volume":123.0},
              {"code":"000002","name":"万科A","price":3.06}
            ]
        """.trimIndent()
        val list = LocalDataService.parseStockList(raw)
        assertEquals(2, list.size)
        assertEquals("000001", list[0].code)
        assertEquals("平安银行", list[0].name)
        assertEquals(11.74, list[0].price)
        assertEquals("000002", list[1].code)
        assertEquals(null, list[1].volume)
    }

    @Test
    fun blankArrayYieldsEmptyList() {
        assertEquals(0, LocalDataService.parseStockList("[]").size)
    }

    @Test
    fun rowMissingCodeIsSkippedInsteadOfFailingWholeList() {
        val raw = """
            [
              {"code":"000001","name":"平安银行","price":11.74},
              {"name":"这一条缺 code，管道或手工编辑都可能造成","price":1.0},
              {"code":"000002","name":"万科A","price":3.06}
            ]
        """.trimIndent()
        val list = LocalDataService.parseStockList(raw)
        assertEquals(2, list.size, "缺 code 的条目应被跳过，其余条目仍应加载")
        assertEquals(listOf("000001", "000002"), list.map { it.code })
    }

    @Test
    fun nonObjectRowIsSkipped() {
        val raw = """[{"code":"000001","name":"平安银行"}, "not-an-object", 42]"""
        val list = LocalDataService.parseStockList(raw)
        assertEquals(1, list.size)
        assertEquals("000001", list[0].code)
    }

    @Test
    fun codeIsNotRequiredToBeNumeric() {
        // 资产里同时有股票与指数/板块，别对 code 做数字假设。
        val raw = """[{"code":"sh000300","name":"沪深300"},{"code":"BK0475","name":"银行"}]"""
        val list = LocalDataService.parseStockList(raw)
        assertEquals(2, list.size)
        assertTrue(list.any { it.code == "sh000300" })
    }
}
