package com.kuikly.stock.data

internal object CompareCards {

    private val compareWords = listOf(
        "对比", "比较", "区别", "差别", "差异", "优劣", "哪个好", "哪个更", "哪个强", "谁强", "谁好",
        "谁更", "选哪个", "怎么选", "如何选", "怎么挑", "横向", "谁值得", "更值得", "值得买",
        "相比", "pk", "PK", "vs", "VS", "还"
    )

    fun maybeBuild(message: String): List<Map<String, Any?>>? {
        if (!compareWords.any { message.contains(it) }) return null
        val mentioned = try { StockDb.detectMentioned(message) } catch (e: Throwable) { return null }
        if (mentioned.size < 2) return null
        val cols = buildList {
            for (s in mentioned.take(3)) {
                val detail = try { StockDb.stockDetail(s.code) } catch (e: Throwable) { null } ?: continue
                val rt = detail.realtime ?: continue
                val price = rt.price ?: continue
                val name = (s.name ?: rt.name ?: s.code) + " " + s.code
                val pct = rt.changePercent ?: 0.0
                val ma5 = detail.indicator?.ma5
                val rsi = detail.indicator?.rsi6
                val recent = detail.kline.orEmpty().takeLast(5)
                val keyLevel = formatComparisonKeyLevel(
                    recent.minOfOrNull { it.low },
                    recent.maxOfOrNull { it.high },
                )
                add(
                    listOf(
                        name,
                        fmt2(price),
                        fmtSignedPct(pct),
                        ma5?.let { fmt2(it) } ?: "-",
                        rsi?.let { fmt2(it) } ?: "-",
                        keyLevel,
                    ).joinToString("|")
                )
            }
        }
        if (cols.size < 2) return null
        return listOf(
            mapOf(
                "type" to "compare_card",
                "title" to "多股对比",
                "headers" to "名称|现价|涨跌幅|MA5|RSI6|关键位",
                "rows" to cols.joinToString("\n")
            )
        )
    }
}

internal fun formatComparisonKeyLevel(support: Double?, resistance: Double?): String {
    if (support == null || resistance == null || !support.isFinite() || !resistance.isFinite()) return "-"
    return "支 ${fmt2(support)} / 压 ${fmt2(resistance)}"
}
