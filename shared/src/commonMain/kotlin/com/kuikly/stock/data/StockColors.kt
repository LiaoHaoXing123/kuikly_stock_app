package com.kuikly.stock.data

/**
 * 全仓统一的涨跌配色（A 股规范：红涨绿跌）。
 *
 * 收敛前仓库里同屏并存三套红绿：
 *   - E53935 / 43A047（详情页/自选/列表/指数/聊天 主体）
 *   - EF4444 / 10B981（板块/行业/资金流卡）
 *   - E64545 / 17A67A（AI 观点/支撑压力）
 * 色相不统一、同屏会撞。这里收敛为单一来源；想整体换色板只改这个文件。
 *
 * 注意：这里只负责“涨/跌/平”方向色。危险色（删除/风险警告，D32F2F）与通用
 * 中性灰文本不在此收敛，避免把不同语义强行绑定到涨跌色上。
 *
 * Kuikly 的 `color(...)` 与 `Color(...)` 均接受 Long（ARGB）。
 */
object StockColors {
    /** 涨 / 正 */
    const val UP: Long = 0xFFEF4444
    /** 跌 / 负 */
    const val DOWN: Long = 0xFF10B981
    /** 平 / 中性 */
    const val FLAT: Long = 0xFF8A9099

    /** 字符串形式（供需要 "#RRGGBB" 的数据层使用） */
    const val UP_HEX: String = "#EF4444"
    const val DOWN_HEX: String = "#10B981"

    private const val UP_RGB: Long = 0xEF4444L
    private const val DOWN_RGB: Long = 0x10B981L

    /** 以指定 alpha(0x00..0xFF) 叠加涨色，返回 ARGB Long */
    fun up(alpha: Int): Long = argb(alpha, UP_RGB)

    /** 以指定 alpha(0x00..0xFF) 叠加跌色，返回 ARGB Long */
    fun down(alpha: Int): Long = argb(alpha, DOWN_RGB)

    /** 按布尔方向取色：true=涨，false=跌 */
    fun byUp(up: Boolean): Long = if (up) UP else DOWN

    /** 按数值方向取色：>0 涨，<0 跌，=0 平 */
    fun byChange(value: Double): Long = when {
        value > 0 -> UP
        value < 0 -> DOWN
        else -> FLAT
    }

    private fun argb(alpha: Int, rgb: Long): Long =
        ((alpha.toLong() and 0xFFL) shl 24) or (rgb and 0xFFFFFFL)
}
