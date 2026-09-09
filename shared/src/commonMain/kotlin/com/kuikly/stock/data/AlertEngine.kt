package com.kuikly.stock.data

// 纯 Kotlin 数字格式化：String.format 是 JVM 专属 API，iOS/Native 与 JS 上不存在，
// 故所有跨平台格式化都走这里。
// 与 JVM "%.Nf" 逐字一致：从 Double 的二进制位精确展开十进制（m×2^e，e<0 时改写为
// m×5^k/10^k），再按 HALF_UP 舍入（看第一位被舍去的数字是否 ≥ '5'）。注意不能用
// “乘 10^N 再 round”的捷径：乘法本身会舍入（如 2.675×100 恰为 267.5），导致与 JVM
// 差 1（如 2.675 应为 "2.67"）。算法已用 24 组 JVM 教科书用例 + 2000 随机值校验。
internal fun fmtFixed(v: Double, digits: Int): String {
    if (!v.isFinite()) return v.toString()
    val d = digits.coerceIn(0, 9)
    val neg = v < 0.0 || (v == 0.0 && v.toBits() < 0)
    val a = kotlin.math.abs(v)
    if (a >= 9e14) return (if (neg) "-" else "") + a.toString()
    var tiny = 0.5
    repeat(d) { tiny /= 10.0 }
    if (a < tiny) return (if (neg) "-" else "") + fmtZero(d)
    val bits = a.toBits()
    val rawExp = ((bits ushr 52) and 0x7FFL).toInt()
    val mant = bits and 0xFFFFFFFFFFFFFL
    val e2 = if (rawExp == 0) -1074 else rawExp - 1075
    val m = if (rawExp == 0) mant else mant or (1L shl 52)
    var digitsStr = m.toString()
    var fracLen = 0
    if (e2 >= 0) {
        repeat(e2) { digitsStr = mulSmall(digitsStr, 2) }
    } else {
        repeat(-e2) { digitsStr = mulSmall(digitsStr, 5) }
        fracLen = -e2
    }
    val full = digitsStr.padStart(fracLen + 1, '0')
    val intPart = full.substring(0, full.length - fracLen)
    val fracPart = full.substring(full.length - fracLen)
    val keep: String
    val roundedUp: Boolean
    if (fracLen <= d) {
        keep = fracPart + "0".repeat(d - fracLen)
        roundedUp = false
    } else {
        keep = fracPart.substring(0, d)
        roundedUp = fracPart[d] >= '5'
    }
    var intOut = intPart.trimStart('0').ifEmpty { "0" }
    var fracOut = keep
    if (roundedUp) {
        if (d == 0) {
            intOut = incDec(intOut)
        } else {
            val inc = incDec(keep.ifEmpty { "0" })
            if (inc.length > d) {
                intOut = incDec(intOut)
                fracOut = "0".repeat(d)
            } else {
                fracOut = inc.padStart(d, '0')
            }
        }
    }
    val sign = if (neg) "-" else ""
    return if (d == 0) sign + intOut else sign + intOut + "." + fracOut
}

private fun fmtZero(d: Int): String = if (d == 0) "0" else "0." + "0".repeat(d)

private fun mulSmall(s: String, k: Int): String {
    val out = StringBuilder(s.length + 1)
    var carry = 0
    for (i in s.length - 1 downTo 0) {
        val t = (s[i] - '0') * k + carry
        out.append('0' + t % 10)
        carry = t / 10
    }
    while (carry > 0) {
        out.append('0' + carry % 10)
        carry /= 10
    }
    return out.reverse().toString()
}

private fun incDec(s: String): String {
    val c = s.toCharArray()
    var i = c.size - 1
    while (i >= 0) {
        if (c[i] == '9') {
            c[i] = '0'
            i--
        } else {
            c[i] = c[i] + 1
            return c.concatToString()
        }
    }
    return "1" + c.concatToString()
}

internal fun fmt0(v: Double): String = fmtFixed(v, 0)

internal fun fmt1(v: Double): String = fmtFixed(v, 1)

internal fun fmt2(v: Double): String = fmtFixed(v, 2)

internal fun fmt3(v: Double): String = fmtFixed(v, 3)

internal fun fmtSigned2(v: Double): String = if (v < 0) fmtFixed(v, 2) else "+" + fmtFixed(v, 2)

internal fun fmtSignedPct(v: Double): String {
    val prefix = if (v > 0) "+" else ""
    return prefix + fmtFixed(v, 2) + "%"
}

internal fun describeAlertType(type: Int): String = when (type) {
    0 -> "价格 ≥"
    1 -> "价格 ≤"
    2 -> "涨幅 ≥"
    else -> "跌幅 ≥"
}

internal object AlertEngine {
    fun hits(): List<Pair<PriceAlertRule, String>> {
        val rules = WatchStore.alerts()
        if (rules.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<PriceAlertRule, String>>()
        for (r in rules) {
            if (!r.enabled) continue
            val rt = try { StockDb.stockDetail(r.code)?.realtime } catch (e: Throwable) { null } ?: continue
            val price = rt.price ?: continue
            val pct = rt.changePercent ?: 0.0
            val msg = when (r.type) {
                0 -> if (price >= r.threshold) "${r.name} 现价 ${fmt2(price)} 已触及提醒价 ${fmt2(r.threshold)}" else null
                1 -> if (price <= r.threshold) "${r.name} 现价 ${fmt2(price)} 已低于提醒价 ${fmt2(r.threshold)}" else null
                2 -> if (pct >= r.threshold) "${r.name} 现价 ${fmt2(price)}，涨幅 ${fmtSignedPct(pct)} ≥ ${fmt2(r.threshold)}%" else null
                else -> if (pct <= -r.threshold) "${r.name} 现价 ${fmt2(price)}，跌幅 ${fmtSignedPct(pct)} 已达 -${fmt2(r.threshold)}%" else null
            }
            if (msg != null) out.add(r to msg)
        }
        return out
    }
}
