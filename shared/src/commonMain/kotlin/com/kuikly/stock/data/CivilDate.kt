package com.kuikly.stock.data

/**
 * 公历日期（无时区）。日历网格、连盈统计都只关心「哪一天」，
 * commonMain 没有 `java.time`，这里用 Howard Hinnant 的 civil/days 互转，
 * 避免各端自己拆字符串。
 */
internal data class CivilDate(val year: Int, val month: Int, val day: Int) : Comparable<CivilDate> {

    val iso: String
        get() = year.toString().padStart(4, '0') + "-" +
            month.toString().padStart(2, '0') + "-" +
            day.toString().padStart(2, '0')

    /** 0=周一 … 6=周日。A 股交易周从周一起，月历也按这个排。 */
    val mondayIndex: Int
        get() {
            val z = toEpochDay()
            return (((z % 7) + 7 + 3) % 7).toInt()
        }

    fun plusDays(n: Int): CivilDate = fromEpochDay(toEpochDay() + n)

    fun plusMonths(delta: Int): CivilDate {
        val total = year * 12 + (month - 1) + delta
        val y = total.floorDiv(12)
        val m = total.mod(12) + 1
        val dim = daysInMonth(y, m)
        return CivilDate(y, m, day.coerceAtMost(dim))
    }

    fun startOfMonth(): CivilDate = CivilDate(year, month, 1)

    fun mondayOfWeek(): CivilDate = plusDays(-mondayIndex)

    fun sundayOfWeek(): CivilDate = plusDays(6 - mondayIndex)

    override fun compareTo(other: CivilDate): Int = toEpochDay().compareTo(other.toEpochDay())

    /**
     * 相对 1970-01-01 的天数。算法来自 Hinnant，1970-01-01 必须为 0。
     */
    fun toEpochDay(): Long {
        var y = year
        val m = month
        val d = day
        if (m <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097 + doe - 719468
    }

    companion object {
        fun fromEpochDay(z: Long): CivilDate {
            val zz = z + 719468
            val era = if (zz >= 0) zz / 146097 else (zz - 146096) / 146097
            val doe = zz - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            var y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = mp + if (mp < 10) 3 else -9
            if (m <= 2) y += 1
            return CivilDate(y.toInt(), m.toInt(), d.toInt())
        }

        fun parse(iso: String): CivilDate? {
            if (iso.length != 10 || iso[4] != '-' || iso[7] != '-') return null
            val y = iso.substring(0, 4).toIntOrNull() ?: return null
            val m = iso.substring(5, 7).toIntOrNull() ?: return null
            val d = iso.substring(8, 10).toIntOrNull() ?: return null
            if (m !in 1..12) return null
            val dim = daysInMonth(y, m)
            if (d !in 1..dim) return null
            return CivilDate(y, m, d)
        }

        fun daysInMonth(year: Int, month: Int): Int {
            if (month == 2) return if (isLeap(year)) 29 else 28
            return if (month == 4 || month == 6 || month == 9 || month == 11) 30 else 31
        }

        fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }
}
