package com.kuikly.stock.data

object StockColors {

    const val UP: Long = 0xFFEF4444

    const val DOWN: Long = 0xFF10B981

    const val FLAT: Long = 0xFF8A9099

    const val UP_HEX: String = "#EF4444"
    const val DOWN_HEX: String = "#10B981"

    private const val UP_RGB: Long = 0xEF4444L
    private const val DOWN_RGB: Long = 0x10B981L

    fun up(alpha: Int): Long = argb(alpha, UP_RGB)

    fun down(alpha: Int): Long = argb(alpha, DOWN_RGB)

    fun byUp(up: Boolean): Long = if (up) UP else DOWN

    fun byChange(value: Double): Long = when {
        value > 0 -> UP
        value < 0 -> DOWN
        else -> FLAT
    }

    private fun argb(alpha: Int, rgb: Long): Long =
        ((alpha.toLong() and 0xFFL) shl 24) or (rgb and 0xFFFFFFL)
}
