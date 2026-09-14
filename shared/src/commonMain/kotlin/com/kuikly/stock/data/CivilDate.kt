package com.kuikly.stock.data

internal data class CivilDate(val year: Int, val month: Int, val day: Int) : Comparable<CivilDate> {

    val iso: String
        get() = year.toString().padStart(4, '0') + "-" +
            month.toString().padStart(2, '0') + "-" +
            day.toString().padStart(2, '0')

    /** 展示用中文格式，如 2026年-09月-01日。 */
    val cn: String
        get() = year.toString().padStart(4, '0') + "年-" +
            month.toString().padStart(2, '0') + "月-" +
            day.toString().padStart(2, '0') + "日"

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

        /**
         * 宽松解析：接受 2026-09-01、2026年-09月-01日、2026年9月1日、2026/09/01 等写法。
         * 掩码框偶尔会多带一位数字（如 `2026年-09月-11日7`），取前三个数字即可，不因此拦下保存。
         */
        fun parseLoose(text: String): CivilDate? {
            val nums = mutableListOf<Int>()
            var cur = StringBuilder()
            fun flush() {
                if (cur.isNotEmpty()) {
                    cur.toString().toIntOrNull()?.let { nums.add(it) }
                    cur = StringBuilder()
                }
            }
            for (ch in text) {
                if (ch.isDigit()) cur.append(ch) else flush()
            }
            flush()
            if (nums.size < 3) return null
            return of(nums[0], nums[1], nums[2])
        }

        /**
         * 日期输入掩码：只保留数字（最多 8 位），并按 `YYYY年-MM月-DD日` 摆放，
         * 「年-」「月-」「日」是固定字符，用户只需要输入数字。
         */
        fun maskDigits(raw: String): String {
            val digits = raw.filter { it.isDigit() }.take(8)
            val sb = StringBuilder()
            for ((i, ch) in digits.withIndex()) {
                sb.append(ch)
                when (i) {
                    3 -> sb.append("年-")
                    5 -> sb.append("月-")
                    7 -> sb.append("日")
                }
            }
            return sb.toString()
        }

        /**
         * 输入框增量掩码：prev 是改动前的显示文本，next 是本次输入后的文本。
         *
         * - 文本变短且数字没少：只是被删掉了掩码字符（"-"、"日"），按删掉最后一位数字处理，
         *   否则掩码会立刻把字符补回来、退格看起来失效。
         * - 文本变短且数字也少了：整段删除或全选后覆盖输入，直接采用剩下的数字。
         * - 文本变长：追加数字。
         */
        fun maskTyping(prev: String, next: String): String {
            val prevDigits = prev.filter { it.isDigit() }.take(8)
            val nextDigits = next.filter { it.isDigit() }.take(8)
            val digits = when {
                next.length >= prev.length -> nextDigits
                nextDigits.length >= prevDigits.length -> prevDigits.dropLast(1)
                else -> nextDigits
            }
            return maskDigits(digits)
        }

        /** 由年月日构造并校验，非法返回 null。 */
        fun of(year: Int, month: Int, day: Int): CivilDate? {
            if (year !in 1..9999 || month !in 1..12) return null
            if (day !in 1..daysInMonth(year, month)) return null
            return CivilDate(year, month, day)
        }

        /** 把 ISO 日期转成展示用中文格式；无法解析时原样返回。 */
        fun toCn(iso: String): String = parse(iso)?.cn ?: iso

        fun daysInMonth(year: Int, month: Int): Int {
            if (month == 2) return if (isLeap(year)) 29 else 28
            return if (month == 4 || month == 6 || month == 9 || month == 11) 30 else 31
        }

        fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }
}
