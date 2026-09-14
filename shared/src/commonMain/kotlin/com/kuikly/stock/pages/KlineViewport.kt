package com.kuikly.stock.pages

// 周期与视口状态可能分批通知，绘制前统一校验当前数据的索引范围。
internal fun klineViewport(total: Int, requestedStart: Int, requestedCount: Int): IntRange {
    if (total <= 0 || requestedCount <= 0) return IntRange.EMPTY
    val count = requestedCount.coerceAtMost(total)
    val start = requestedStart.coerceIn(0, total - count)
    return start until start + count
}
