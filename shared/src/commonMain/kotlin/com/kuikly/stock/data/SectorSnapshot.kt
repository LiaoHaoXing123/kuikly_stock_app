package com.kuikly.stock.data

/**
 * 官方行业板块快照（东方财富·官方行业板块）。
 * 数据来自 data-pipeline/build_sector.py：sector_board / sector_member 表。
 * 涨跌幅为百分数（3.2 = 3.2%）；总市值为元；快照用 fetchDate 标注时效。
 */
data class SectorBoardItem(
    val boardCode: String,          // BKxxxx
    val boardName: String,
    val changePercent: Double?,     // 板块涨跌幅（%）
    val leader: String?,            // 领涨股名称
    val leaderChange: Double?,      // 领涨股涨跌幅（%）
    val totalMv: Double?,           // 总市值（元）
    val turnover: Double?,          // 换手率（%）
    val upCount: Int,               // 上涨家数
    val downCount: Int,             // 下跌家数
    val fetchDate: String,          // 快照日期 YYYY-MM-DD
)

data class SectorMemberItem(
    val code: String,
    val name: String?,
    val price: Double?,
    val changePercent: Double?,     // 百分数
)

data class SectorSnapshot(
    val board: SectorBoardItem,
    val members: List<SectorMemberItem>,
) {
    /** 与板块同日期且报价有效的成分股（价格>0、涨跌幅有限）。 */
    val current: List<SectorMemberItem> get() = members.filter {
        it.price?.let { p -> p.isFinite() && p > 0 } == true &&
            it.changePercent?.isFinite() == true
    }

    /** 成分股等权平均涨跌幅（%）。 */
    val average: Double? get() = current.mapNotNull { it.changePercent }.takeIf { it.isNotEmpty() }?.average()

    /** 个股相对板块等权均值的百分点（个股涨跌幅 - 样本均值）。 */
    fun relative(code: String): Double? {
        val own = current.firstOrNull { it.code == code }?.changePercent ?: return null
        return average?.let { own - it }
    }

    /** 个股在板块内的涨幅排名（1 起；无效数据返回 null）。 */
    fun rankOf(code: String): Int? {
        val ranked = current.sortedByDescending { it.changePercent }
        val idx = ranked.indexOfFirst { it.code == code }
        return if (idx >= 0) idx + 1 else null
    }

    /** AI 提示词证据串。 */
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
