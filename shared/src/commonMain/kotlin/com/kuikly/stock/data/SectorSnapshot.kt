package com.kuikly.stock.data

data class SectorBoardItem(
    val boardCode: String,
    val boardName: String,
    val changePercent: Double?,
    val leader: String?,
    val leaderChange: Double?,
    val totalMv: Double?,
    val turnover: Double?,
    val upCount: Int,
    val downCount: Int,
    val fetchDate: String,
)

data class SectorMemberItem(
    val code: String,
    val name: String?,
    val price: Double?,
    val changePercent: Double?,
)

data class SectorSnapshot(
    val board: SectorBoardItem,
    val members: List<SectorMemberItem>,
) {

    val current: List<SectorMemberItem> get() = members.filter {
        it.price?.let { p -> p.isFinite() && p > 0 } == true &&
            it.changePercent?.isFinite() == true
    }

    val average: Double? get() = current.mapNotNull { it.changePercent }.takeIf { it.isNotEmpty() }?.average()

    fun relative(code: String): Double? {
        val own = current.firstOrNull { it.code == code }?.changePercent ?: return null
        return average?.let { own - it }
    }

    fun rankOf(code: String): Int? {
        val ranked = current.sortedByDescending { it.changePercent }
        val idx = ranked.indexOfFirst { it.code == code }
        return if (idx >= 0) idx + 1 else null
    }

    fun evidence(code: String): String = buildString {
        append("官方板块：${board.boardName}（东方财富行业板块，代码 ${board.boardCode}）；")
        append("快照日期 ${board.fetchDate}。")
        board.changePercent?.let { append("板块涨跌幅 ${fmtSignedPct(it)}；") }
        board.leader?.let { append("领涨股 ${it}${board.leaderChange?.let { c -> " ${fmtSignedPct(c)}" } ?: ""}；") }
        append("板块内 ${board.upCount}涨${board.downCount}跌。")
        average?.let { append("成分股等权平均涨跌幅 ${fmtSignedPct(it)}；") }
        relative(code)?.let { append("个股相对板块均值 ${fmtSigned2(it)} 个百分点；") }
        rankOf(code)?.let { append("板块内涨幅排名 ${it}/${current.size}。") }
        append("板块为当日官方快照，非本地估算；快照可能不含全部成分股或非实时。")
    }
}
