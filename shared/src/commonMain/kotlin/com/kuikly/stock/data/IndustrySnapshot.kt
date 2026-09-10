package com.kuikly.stock.data

import com.kuikly.stock.pages.StockListItem

data class IndustryMember(val quote: StockListItem, val date: String)
data class IndustrySnapshot(val name: String, val members: List<IndustryMember>) {
    val date: String get() = members.map { it.date.take(10) }.filter { it.length == 10 }.maxOrNull().orEmpty()
    val current: List<IndustryMember> get() = members.filter {
        it.date.take(10) == date && date.isNotEmpty() &&
            it.quote.price?.let { p -> p.isFinite() && p > 0 } == true &&
            it.quote.changePercent?.isFinite() == true
    }
    val average: Double? get() = current.mapNotNull { it.quote.changePercent }.takeIf { it.isNotEmpty() }?.average()
    fun relative(code: String): Double? {
        val own = current.firstOrNull { it.quote.code == code }?.quote?.changePercent ?: return null
        return average?.let { own - it }
    }
    fun evidence(code: String): String = buildString {
        append("行业对照：$name；行情快照日期 $date；同日有效样本 ${current.size}/${members.size}。")
        average?.let { append("样本等权平均涨跌幅 ${fmtSignedPct(it)}；") }
        relative(code)?.let { append("个股相对样本均值 ${fmtSigned2(it)} 个百分点。") }
        append("这是本地同业样本统计，不是官方板块指数，不能代表完整市场或实时行情。")
    }
}
