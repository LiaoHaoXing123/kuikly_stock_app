package com.kuikly.stock.pages

import com.kuikly.stock.data.SectorBoardItem
import com.kuikly.stock.data.SectorMemberItem
import com.kuikly.stock.data.SectorSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SectorSnapshotTest {

    private fun board(change: Double? = 2.0) = SectorBoardItem(
        boardCode = "BK1201", boardName = "电子",
        changePercent = change, leader = "某股", leaderChange = 10.0,
        totalMv = 1.2e12, turnover = 3.0, upCount = 10, downCount = 2,
        fetchDate = "2026-09-10",
    )

    @Test fun currentFiltersInvalidQuotes() {
        val snap = SectorSnapshot(board(), listOf(
            SectorMemberItem("600001", "甲", 10.0, 4.0),
            SectorMemberItem("600002", "乙", 11.0, -2.0),
            SectorMemberItem("600003", "坏价", 0.0, 20.0),
            SectorMemberItem("600004", "无涨跌", 9.0, null),
        ))
        assertEquals(2, snap.current.size)
        assertEquals(1.0, snap.average)
        assertEquals(3.0, snap.relative("600001"))
        assertNull(snap.relative("600004"))
    }

    @Test fun rankOfSortsByChangePercent() {
        val snap = SectorSnapshot(board(), listOf(
            SectorMemberItem("a", "甲", 10.0, 5.0),
            SectorMemberItem("b", "乙", 10.0, 1.0),
            SectorMemberItem("c", "丙", 10.0, 3.0),
        ))
        assertEquals(1, snap.rankOf("a"))
        assertEquals(2, snap.rankOf("c"))
        assertEquals(3, snap.rankOf("b"))
        assertNull(snap.rankOf("zzz"))
    }

    @Test fun emptySnapshotHasNoStats() {
        val snap = SectorSnapshot(board(), emptyList())
        assertNull(snap.average)
        assertNull(snap.relative("a"))
        assertNull(snap.rankOf("a"))
    }

    @Test fun evidenceMentionsBoardDateAndRank() {
        val snap = SectorSnapshot(board(), listOf(
            SectorMemberItem("a", "甲", 10.0, 5.0),
            SectorMemberItem("b", "乙", 10.0, 1.0),
        ))
        val ev = snap.evidence("b")
        assertEquals(true, ev.contains("官方板块：电子"))
        assertEquals(true, ev.contains("快照日期 2026-09-10"))
        assertEquals(true, ev.contains("板块内涨幅排名 2/2"))
        assertEquals(true, ev.contains("非本地估算"))
    }
}
