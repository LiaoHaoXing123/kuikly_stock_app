package com.kuikly.stock.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CivilDateTest {
    @Test fun epochDayOfUnixOriginIsZero() {
        assertEquals(0, CivilDate(1970, 1, 1).toEpochDay())
        assertEquals(CivilDate(1970, 1, 1), CivilDate.fromEpochDay(0))
    }

    @Test fun knownWeekdays() {
        assertEquals(0, CivilDate(2024, 1, 1).mondayIndex) // 周一
        assertEquals(3, CivilDate(1970, 1, 1).mondayIndex) // 周四
        assertEquals(4, CivilDate(2026, 9, 11).mondayIndex) // 周五
    }

    @Test fun parseAndLeapFebruary() {
        assertEquals(CivilDate(2024, 2, 29), CivilDate.parse("2024-02-29"))
        assertNull(CivilDate.parse("2023-02-29"))
        assertNull(CivilDate.parse("2026/09/11"))
        assertEquals(29, CivilDate.daysInMonth(2024, 2))
        assertEquals(28, CivilDate.daysInMonth(2023, 2))
    }

    @Test fun monthWalkAndWeekBounds() {
        val d = CivilDate(2026, 1, 31)
        assertEquals("2026-02-28", d.plusMonths(1).iso)
        assertEquals("2025-12-31", d.plusMonths(-1).iso)
        val monday = CivilDate(2026, 9, 11).mondayOfWeek()
        assertEquals("2026-09-07", monday.iso)
        assertEquals(0, monday.mondayIndex)
        assertEquals("2026-09-13", CivilDate(2026, 9, 11).sundayOfWeek().iso)
    }
}

class HoldingCalendarLogicTest {
    private fun holding(code: String, shares: Double = 100.0, cost: Double = 10.0, name: String = "测") =
        WatchHolding(code, name, shares, cost)

    private fun quote(price: Double, change: Double, pct: Double, name: String = "测") =
        QuoteProbe(name, price, change, pct, name)

    @Test fun emptyHoldingsDoNotCreateADay() {
        assertNull(buildHoldingDaySnapshot("2026-09-11", emptyList(), { null }, 0.5, emptyList()))
        assertNull(
            buildHoldingDaySnapshot(
                "2026-09-11",
                listOf(holding("600000", shares = 0.0)),
                { quote(10.0, 0.2, 2.0) },
                0.5,
                emptyList(),
            )
        )
    }

    @Test fun dayPnlIsChangeTimesSharesAndPctUsesYesterdayValue() {
        val snap = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("600000", shares = 100.0)),
            { quote(11.0, 1.0, 10.0, "浦发") },
            hs300Pct = 2.0,
            alertHits = emptyList(),
        )
        assertNotNull(snap)
        assertEquals(1100.0, snap.marketValue)
        assertEquals(100.0, snap.dayPnl)
        assertEquals(10.0, snap.dayPnlPct, 1e-9)
        assertEquals(true, snap.beatHs300)
    }

    @Test fun loseToHs300WhenPortfolioLags() {
        val snap = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("600000", shares = 100.0)),
            { quote(10.1, 0.1, 1.0) },
            hs300Pct = 2.0,
            alertHits = emptyList(),
        )
        assertEquals(false, snap!!.beatHs300)
    }

    @Test fun missingHs300MeansNoBeatFlag() {
        val snap = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("600000")),
            { quote(10.0, 0.0, 0.0) },
            hs300Pct = null,
            alertHits = emptyList(),
        )
        assertNull(snap!!.beatHs300)
    }

    @Test fun contributionSortedByAbsPnl() {
        val quotes = mapOf(
            "600000" to quote(11.0, 1.0, 10.0, "浦发"),
            "600519" to quote(1400.0, -50.0, -3.4, "茅台"),
        )
        val snap = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("600000", 100.0, name = "浦发"), holding("600519", 10.0, name = "茅台")),
            { quotes[it] },
            0.0,
            emptyList(),
        )
        assertEquals(listOf("600519", "600000"), snap!!.holdings.map { it.code })
        assertEquals(-500.0 + 100.0, snap.dayPnl, 1e-9)
    }

    @Test fun limitClassificationUsesBoardCap() {
        assertEquals("up", classifyLimit(9.99, 10.0))
        assertEquals("down", classifyLimit(-9.99, 10.0))
        assertEquals("", classifyLimit(8.0, 10.0))
        assertEquals("up", classifyLimit(19.99, 20.0))
        assertEquals(10.0, limitThresholdPercent("600000", "浦发"))
        assertEquals(20.0, limitThresholdPercent("300750", "宁德时代"))
        assertEquals(20.0, limitThresholdPercent("688981", "中芯国际"))
        assertEquals(30.0, limitThresholdPercent("830799", "联域股份"))
        assertEquals(30.0, limitThresholdPercent("430047", "诺思兰德"))
        assertEquals(5.0, limitThresholdPercent("600000", "ST 假股"))
        val snap = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("300750", 100.0, name = "宁德时代")),
            { quote(200.0, 39.98, 19.99, "宁德时代") },
            0.0,
            emptyList(),
        )
        assertEquals("up", snap!!.holdings.single().limit)
        assertTrue(snap.events.any { it.kind == CAL_EVENT_LIMIT_UP && it.code == "300750" })
    }

    @Test fun alertHitsAttachOnlyForHeldCodes() {
        val hits = listOf(
            PriceAlertRule("600000", "浦发", 0, 12.0) to "浦发到价",
            PriceAlertRule("000001", "平安", 0, 12.0) to "平安到价",
        )
        val snap = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("600000", name = "浦发")),
            { quote(12.5, 0.5, 4.0, "浦发") },
            0.0,
            hits,
        )
        assertEquals(1, snap!!.events.count { it.kind == CAL_EVENT_ALERT })
        assertEquals("浦发到价", snap.events.single { it.kind == CAL_EVENT_ALERT }.label)
    }

    @Test fun extraDividendAndEarningsSurvive() {
        val extra = listOf(
            CalendarEventMark(CAL_EVENT_DIVIDEND, "600000", "浦发 除权"),
            CalendarEventMark(CAL_EVENT_EARNINGS, "600000", "浦发 财报"),
        )
        val snap = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("600000")),
            { quote(10.0, 0.0, 0.0) },
            0.0,
            emptyList(),
            extra,
        )
        assertTrue(snap!!.events.any { it.kind == CAL_EVENT_DIVIDEND })
        assertTrue(snap.events.any { it.kind == CAL_EVENT_EARNINGS })
    }

    @Test fun statsWinRateStreakAndMonth() {
        val days = listOf(
            day("2026-08-29", 10.0),
            day("2026-09-01", 5.0),
            day("2026-09-02", -2.0),
            day("2026-09-03", 3.0),
            day("2026-09-04", 4.0),
        )
        val s = calendarStatsOf(days, "2026-09")
        assertEquals(10.0, s.monthPnl, 1e-9)
        assertEquals(4, s.monthDays)
        assertEquals(3, s.winDays)
        assertEquals(1, s.lossDays)
        assertEquals(75.0, s.winRate, 1e-9)
        assertEquals(2, s.streak)
        assertEquals(10.0, s.maxWin, 1e-9)
        assertEquals(-2.0, s.maxLoss, 1e-9)
    }

    @Test fun losingStreakIsNegative() {
        val days = listOf(day("2026-09-01", 1.0), day("2026-09-02", -1.0), day("2026-09-03", -3.0))
        assertEquals(-2, calendarStatsOf(days, "2026-09").streak)
    }

    @Test fun storeOverwritesSameDayAndDoesNotInventHistory() {
        val mem = mutableMapOf<String, String>()
        val repo = HoldingCalendarRepository({ mem[it] }, { k, v -> mem[k] = v })
        repo.upsert(day("2026-09-11", 1.0))
        repo.upsert(day("2026-09-11", 5.0))
        assertEquals(1, repo.days().size)
        assertEquals(5.0, repo.find("2026-09-11")!!.dayPnl)
        assertNull(repo.find("2026-09-10"))
    }

    @Test fun roundTripKeepsHs300AndEvents() {
        val original = buildHoldingDaySnapshot(
            "2026-09-11",
            listOf(holding("600000", name = "浦发")),
            { quote(11.0, 1.0, 10.0, "浦发") },
            hs300Pct = 0.45,
            alertHits = listOf(PriceAlertRule("600000", "浦发", 0, 10.0) to "到价"),
        )!!
        val decoded = decodeDays(encodeDays(listOf(original))).single()
        assertEquals(original.date, decoded.date)
        assertEquals(0.45, decoded.hs300Pct!!, 1e-9)
        assertEquals(true, decoded.beatHs300)
        assertTrue(decoded.events.any { it.kind == CAL_EVENT_ALERT })
        assertEquals(1, decoded.holdings.size)
    }

    @Test fun heatColorIsNeutralWithoutRange() {
        val emptyColor = 0xFFE8ECF1L
        assertEquals(emptyColor, heatColor(0.0, 10.0, emptyColor))
        assertEquals(emptyColor, heatColor(5.0, 0.0, emptyColor))
        assertTrue(heatColor(8.0, 10.0, emptyColor) != heatColor(-8.0, 10.0, emptyColor))
    }

    @Test fun corruptPrefsDecodeEmpty() {
        assertTrue(decodeDays("not-json").isEmpty())
        assertTrue(decodeDays(null).isEmpty())
    }

    @Test fun heatStripPadsToWeekBounds() {
        val days = listOf(day("2026-09-11", 12.0), day("2026-09-14", -4.0))
        val cells = heatStrip(days, 12.0)
        assertEquals("2026-09-07", cells.first().date)
        assertEquals("2026-09-20", cells.last().date)
        assertTrue(cells.first { it.date == "2026-09-11" }.hasData)
        assertFalse(cells.first { it.date == "2026-09-12" }.hasData)
    }

    @Test fun compactPnlUsesWanOverTenThousand() {
        assertEquals("+1.5万", compactPnl(15000.0))
        assertEquals("-200", compactPnl(-200.0))
        assertEquals("0", compactPnl(0.0))
    }

    @Test fun september2026GridStartsOnMondayAugust31() {
        val snap = day("2026-09-11", 12.0, hs = 1.0)
        val weeks = monthCells(2026, 9, mapOf(snap.date to snap), 12.0)
        assertEquals(6, weeks.size)
        assertEquals(7, weeks[0].size)
        assertEquals("2026-08-31", weeks[0][0].date)
        assertFalse(weeks[0][0].inMonth)
        assertEquals("2026-09-11", weeks[1][4].date)
        assertEquals("赢", weeks[1][4].vsHs300)
        assertTrue(weeks[1][4].inMonth)
    }

    private fun day(date: String, pnl: Double, hs: Double? = null) = CalendarDaySnapshot(
        date = date,
        marketValue = 1000.0 + pnl,
        dayPnl = pnl,
        dayPnlPct = pnl / 10.0,
        hs300Pct = hs,
    )
}
