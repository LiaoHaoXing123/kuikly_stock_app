package com.kuikly.stock.data

import com.kuikly.stock.pages.KLineDataItem
import kotlin.test.*

class DatedPortfolioTest {
    private fun bars(code: String, vararg closes: Double) = closes.mapIndexed { i, v ->
        KLineDataItem(code, "2026-09-${(i + 7).toString().padStart(2, '0')}", v, v, v, v, 100.0, null)
    }
    @Test fun differentEntryDatesUseCostOnceThenSumDailyChanges() {
        val positions = listOf(WatchHolding("a", "A", 100.0, 9.0, "2026-09-08"),
            WatchHolding("b", "B", 200.0, 20.0, "2026-09-09"))
        val days = datedPortfolioDays(positions, mapOf("a" to bars("a", 10.0, 11.0, 12.0, 11.0),
            "b" to bars("b", 20.0, 20.0, 21.0, 19.0)))
        assertEquals(listOf("2026-09-08", "2026-09-09", "2026-09-10"), days.map { it.date })
        assertEquals(listOf(200.0, 300.0, -500.0), days.map { it.dayPnl })
        assertEquals(listOf(1, 2, 2), days.map { it.holdings.size })
        assertEquals(0.0, days.sumOf { it.dayPnl })
    }
    @Test fun missingQuoteDoesNotBecomePartialPortfolioTotal() {
        val positions = listOf(WatchHolding("a", "A", 100.0, 9.0, "2026-09-07"),
            WatchHolding("b", "B", 100.0, 9.0, "2026-09-08"))
        val days = datedPortfolioDays(positions, mapOf("a" to bars("a", 10.0, 11.0)))
        assertEquals(listOf("2026-09-07"), days.map { it.date })
        assertTrue(datedPortfolioDays(listOf(positions[0].copy(startDate = "")), mapOf("a" to bars("a", 10.0))).isEmpty())
    }
    @Test fun recomputeUsesChangedDatesAndQuantities() {
        val history = mapOf("a" to bars("a", 10.0, 11.0, 12.0))
        val p = WatchHolding("a", "A", 100.0, 10.0, "2026-09-07")
        assertEquals(3, datedPortfolioDays(listOf(p), history).size)
        val changed = datedPortfolioDays(listOf(p.copy(shares = 200.0, startDate = "2026-09-09")), history)
        assertEquals(1, changed.size)
        assertEquals(400.0, changed.single().dayPnl)
        assertTrue(datedPortfolioDays(emptyList(), history).isEmpty())
    }
    @Test fun startDateSurvivesStorageAndLegacyDateStaysUnknown() {
        val memory = mutableMapOf<String, String>()
        val store = WatchRepository({ memory[it] }, { k,v -> memory[k] = v })
        val p = WatchHolding("a", "A", 100.0, 9.0, "2026-09-07")
        store.updateHolding(p)
        assertEquals(p, store.find("a"))
        store.clearHolding("a")
        assertEquals("", store.find("a")!!.startDate)
        memory["watch_v1"] = """{"items":[{"code":"b","name":"B","shares":100,"cost":9}]}"""
        assertEquals("", store.find("b")!!.startDate)
    }
    @Test fun weekendEntryUsesCostAtNextTradingDayWithoutFakeLimitEvent() {
        val friday = bars("a", 10.0).single().copy(tradeDate = "2026-09-04")
        val monday = friday.copy(tradeDate = "2026-09-07", close = 10.1)
        val p = WatchHolding("a", "A", 100.0, 8.0, "2026-09-05")
        val day = datedPortfolioDays(listOf(p), mapOf("a" to listOf(friday, monday))).single()
        assertEquals(210.0, day.dayPnl, 1e-8)
        assertTrue(day.events.isEmpty())
    }

    @Test fun endDateClipsTheRangeAndStopsCountingAfterExit() {
        val history = mapOf("a" to bars("a", 10.0, 11.0, 12.0, 11.0))
        val open = WatchHolding("a", "A", 100.0, 9.0, "2026-09-07")
        assertEquals(
            listOf("2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10"),
            datedPortfolioDays(listOf(open), history).map { it.date },
        )
        val closed = open.copy(endDate = "2026-09-09")
        assertEquals(
            listOf("2026-09-07", "2026-09-08", "2026-09-09"),
            datedPortfolioDays(listOf(closed), history).map { it.date },
        )
        assertEquals(300.0, datedPortfolioDays(listOf(closed), history).sumOf { it.dayPnl })
    }

    @Test fun endDateOnlyClipsTheNamedHolding() {
        val history = mapOf(
            "a" to bars("a", 10.0, 11.0, 12.0),
            "b" to bars("b", 20.0, 21.0, 22.0),
        )
        val positions = listOf(
            WatchHolding("a", "A", 100.0, 9.0, "2026-09-07", "2026-09-08"),
            WatchHolding("b", "B", 100.0, 19.0, "2026-09-07"),
        )
        val days = datedPortfolioDays(positions, history)
        assertEquals(listOf("2026-09-07", "2026-09-08", "2026-09-09"), days.map { it.date })
        assertEquals(listOf(2, 2, 1), days.map { it.holdings.size })
        assertEquals("b", days.last().holdings.single().code)
    }

    @Test fun reversedRangeIsNotACalendarPosition() {
        val bad = WatchHolding("a", "A", 100.0, 9.0, "2026-09-08", "2026-09-07")
        assertFalse(bad.hasCalendarPosition())
        assertTrue(datedPortfolioDays(listOf(bad), mapOf("a" to bars("a", 10.0, 11.0))).isEmpty())
        assertFalse(WatchHolding("a", "A", 100.0, 9.0, "2026-09-08", "2026-09-xx").hasCalendarPosition())
    }

    @Test fun chineseRangeFormatParsesAndRenders() {
        val start = CivilDate.parseLoose("2026年-09月-01日")
        assertNotNull(start)
        assertEquals("2026-09-01", start.iso)
        assertEquals("2026年-09月-01日", start.cn)
        assertEquals(start, CivilDate.parseLoose("2026-09-01"))
        assertEquals(start, CivilDate.parseLoose("2026/9/1"))
        assertNull(CivilDate.parseLoose("2026-13-01"))
        assertNull(CivilDate.parseLoose("2026-09"))
        // 掩码框偶尔多带一位数字，取前三个数字即可，不该因此拦下保存。
        assertEquals(start, CivilDate.parseLoose("2026年-09月-01日7"))
        assertEquals("2026年-09月-01日", CivilDate.toCn("2026-09-01"))
    }

    @Test fun maskKeepsChineseSeparatorsAndOnlyDigitsCanChange() {
        assertEquals("2026年-09月-01日", CivilDate.maskDigits("20260901"))
        assertEquals("2026年-09月-01日", CivilDate.maskDigits("2026年-09月-01日"))
        assertEquals("2026年-", CivilDate.maskDigits("2026"))
        assertEquals("2026年-09月-", CivilDate.maskDigits("2026-09"))
        assertEquals("", CivilDate.maskDigits(""))
        // 掩码与展示格式必须一致，否则回显和输入会打架。
        assertEquals(CivilDate(2026, 9, 1).cn, CivilDate.maskDigits("20260901"))
    }

    @Test fun maskTypingAppendsDigitsAndBackspaceDropsLastOne() {
        // 空框逐位输入：年月日自动补齐。
        assertEquals("2", CivilDate.maskTyping("", "2"))
        assertEquals("2026年-", CivilDate.maskTyping("202", "2026"))
        assertEquals("2026年-09月-", CivilDate.maskTyping("2026年-0", "2026年-09"))
        assertEquals("2026年-09月-01日", CivilDate.maskTyping("2026年-09月-0", "2026年-09月-01"))
        // 已填满再输入数字不会溢出。
        assertEquals("2026年-09月-01日", CivilDate.maskTyping("2026年-09月-01日", "2026年-09月-01日1"))
        // 退格按「删掉最后一位数字」处理，否则掩码会立刻把字符补回来、删不掉。
        assertEquals("2026年-09月-0", CivilDate.maskTyping("2026年-09月-01日", "2026年-09月-01"))
        assertEquals("2026年-09月-", CivilDate.maskTyping("2026年-09月-01日", "2026年-09月-"))
        // 一次删掉多位数字（长按删除 / 全选覆盖 / 清空）时直接采用剩下的数字。
        assertEquals("", CivilDate.maskTyping("2", ""))
        assertEquals("", CivilDate.maskTyping("2026年-09月-01日", ""))
        assertEquals("2", CivilDate.maskTyping("2026年-09月-01日", "2"))
        assertEquals("2026年-09月-01日", CivilDate.maskTyping("2026年-09月-01日", "2026年-09月-01日"))
    }

    @Test fun holdingPeriodSurvivesStorageAndRendersChineseRange() {
        val memory = mutableMapOf<String, String>()
        val store = WatchRepository({ memory[it] }, { k, v -> memory[k] = v })
        val p = WatchHolding("a", "A", 100.0, 9.0, "2026-09-01", "2026-09-14")
        store.updateHolding(p)
        assertEquals(p, store.find("a"))
        assertEquals("2026年-09月-01日 到 2026年-09月-14日", store.find("a")!!.periodCn)
        assertEquals("2026年-09月-01日 至今", p.copy(endDate = "").periodCn)
        store.clearHolding("a")
        assertEquals("", store.find("a")!!.endDate)
    }
}
