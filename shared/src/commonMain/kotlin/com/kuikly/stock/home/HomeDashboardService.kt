package com.kuikly.stock.home

import com.kuikly.stock.data.AlertEngine
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.nowMillis

internal data class DashboardBrief(
    val headline: String,
    val summary: String,
    val marketLabel: String,
    val total: Int,
    val up: Int,
    val down: Int,
    val flat: Int,
    val watchSignals: Int,
    val alertCount: Int,
    val dataDate: String,
)

internal enum class DashboardFocusKind { ALERT, WATCH, EMPTY }

internal data class DashboardFocusItem(
    val title: String,
    val subtitle: String,
    val tag: String,
    val kind: DashboardFocusKind,
)

internal data class HomeDashboardSnapshot(
    val brief: DashboardBrief,
    val focusItems: List<DashboardFocusItem>,
)

internal fun buildDashboardBrief(
    total: Int,
    up: Int,
    down: Int,
    flat: Int,
    watchSignals: Int,
    alertCount: Int,
    dataDate: String,
): DashboardBrief {
    val (headline, summary, marketLabel) = when {
        up > down -> Triple(
            "顺势观察，精选强势",
            "上涨 $up 家、下跌 $down 家，涨跌家数偏强；仍需结合仓位与关键价位。",
            "市场偏强",
        )
        down > up -> Triple(
            "先看风险，再找机会",
            "上涨 $up 家、下跌 $down 家，涨跌家数偏弱；优先处理提醒和持仓风险。",
            "市场偏弱",
        )
        else -> Triple(
            "控制节奏，等待方向",
            "涨跌家数接近平衡，市场处于震荡状态，避免追涨杀跌。",
            "市场震荡",
        )
    }
    return DashboardBrief(
        headline = headline,
        summary = summary,
        marketLabel = marketLabel,
        total = total,
        up = up,
        down = down,
        flat = flat,
        watchSignals = watchSignals,
        alertCount = alertCount,
        dataDate = dataDate.ifBlank { "待更新" },
    )
}

internal fun buildFocusItems(
    alertMessages: List<String>,
    watchSignals: List<String>,
    limit: Int,
): List<DashboardFocusItem> {
    if (limit <= 0) return emptyList()
    val items = buildList {
        alertMessages.forEach { message ->
            add(DashboardFocusItem(message, "请核对现价与交易计划", "提醒触发", DashboardFocusKind.ALERT))
        }
        watchSignals.forEach { signal ->
            add(DashboardFocusItem(signal, "来自自选仓本地指标", "自选信号", DashboardFocusKind.WATCH))
        }
    }.take(limit)
    return items.ifEmpty {
        listOf(
            DashboardFocusItem(
                title = "暂无需要立即处理的信号",
                subtitle = "添加自选仓或价格提醒后，这里会优先展示重点",
                tag = "今日关注",
                kind = DashboardFocusKind.EMPTY,
            )
        )
    }
}

internal object HomeDashboardService {
    private const val CACHE_MS = 30_000L
    private var cachedAt = 0L
    private var cached: HomeDashboardSnapshot? = null

    fun invalidate() {
        cachedAt = 0L
        cached = null
    }

    fun snapshot(force: Boolean = false): HomeDashboardSnapshot {
        val now = nowMillis()
        cached?.takeIf { !force && now - cachedAt < CACHE_MS }?.let { return it }

        val overview = runCatching { StockDb.marketOverview() }.getOrNull()
        val alerts = runCatching { AlertEngine.hits().map { it.second } }.getOrDefault(emptyList())
        val watchSignals = runCatching {
            WatchStore.list().mapNotNull { holding ->
                val detail = StockDb.stockDetail(holding.code) ?: return@mapNotNull null
                val price = detail.realtime?.price ?: return@mapNotNull null
                val ma5 = detail.indicator?.ma5 ?: return@mapNotNull null
                val name = holding.name.ifBlank { holding.code }
                if (price >= ma5) "$name 站上 MA5" else "$name 位于 MA5 下方"
            }
        }.getOrDefault(emptyList())
        // 数据日期走全库唯一口径：日线表里最新的交易日，和个股详情页读的是同一张表。
        // 原实现是「拿自选列表第一只股票的日期当全市场日期」，于是同一个库出现两个日期：
        // 实测该库 25 只已更新到 09-11、其余停在 09-10，首页取到万科A(09-10)、
        // 用户在看平安银行(09-11)，就对不上了；自选为空时还会退化成「待更新」。
        val dataDate = runCatching { StockDb.latestTradeDate() }.getOrNull().orEmpty()

        // 卡片计数：自选仓总数（每只都在监控信号）+ 已启用的价格提醒总数（含未触发的）
        val watchTotal = runCatching { WatchStore.list().size }.getOrDefault(0)
        val alertTotal = runCatching { WatchStore.alerts().count { it.enabled } }.getOrDefault(0)

        val brief = buildDashboardBrief(
            total = overview?.total ?: 0,
            up = overview?.up ?: 0,
            down = overview?.down ?: 0,
            flat = overview?.flat ?: 0,
            watchSignals = watchTotal,
            alertCount = alertTotal,
            dataDate = compactDate(dataDate),
        )
        return HomeDashboardSnapshot(
            brief = brief,
            focusItems = buildFocusItems(alerts, watchSignals, limit = 4),
        ).also {
            cachedAt = now
            cached = it
        }
    }
}

private fun compactDate(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.length >= 8 -> digits.substring(4, 6) + "-" + digits.substring(6, 8)
        raw.length >= 5 -> raw.takeLast(5)
        else -> raw
    }
}
