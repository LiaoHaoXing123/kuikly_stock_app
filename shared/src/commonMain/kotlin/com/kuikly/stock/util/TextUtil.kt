// 纯文本/字符串工具。
//
// 这里只放「不依赖任何 Kuikly 视图、不依赖 Pager」的函数——它们可以直接被单测覆盖，
// 也可以被 data / network 层复用。凡是需要 Module、View 或 observable 的，不属于这里。

package com.kuikly.stock.util

/**
 * 把各种换行写法归一成 `\n`。
 *
 * AI 返回的文本里换行有三种来源：真正的 `\n`、HTML 的 `<br>` / `<br/>`、
 * 以及被 JSON 再转义一次的 `\\n`（字面量反斜杠 + n）。卡片渲染按行切分，
 * 不先归一就会出现「一整段挤在一行」或「行首多一个 \n 字面量」。
 */
internal fun normalizeBreaks(s: String): String {
    if (s.isEmpty()) return s
    var t = s.replace("<br/>", "\n").replace("<br>", "\n")
    t = t.replace("\r\n", "\n")
    t = t.replace('\r', '\n')
    return t.replace("\\n", "\n")
}

/** 归一换行后按行切分，丢掉空行与行首尾空白。用于把一段文本铺成多行列表。 */
internal fun splitBreaks(s: String): List<String> {
    return normalizeBreaks(s).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 相对时间描述：「刚刚 / 12 分钟前 / 3 小时前 / 2 天前 / 3 周前 / 更早」。
 *
 * 用「距今多久」而不是「几月几号」，是因为 commonMain 拿不到本地时区，
 * 按 UTC 换算日历日会在半夜前后错一天。时长差没有这个问题。
 */
internal fun relativeTimeLabel(at: Long, now: Long): String {
    val diff = now - at
    if (diff < MINUTE_MS) return "刚刚"
    if (diff < HOUR_MS) return "${diff / MINUTE_MS} 分钟前"
    if (diff < DAY_MS) return "${diff / HOUR_MS} 小时前"
    val days = diff / DAY_MS
    if (days < 7) return "$days 天前"
    val weeks = days / 7
    return if (weeks < 8) "$weeks 周前" else "更早"
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
