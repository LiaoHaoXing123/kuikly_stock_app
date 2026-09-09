package com.kuikly.stock.ai.protocol

/**
 * 通用卡片协议校验器。
 * - 必填字段缺失/非法 -> 整个对象作废
 * - 可选字段非法      -> 只剥离该字段，对象保留（字段级降级）
 * 聊天 v1 与详情 v2 共用此文件，各自只注册 registry。
 */
sealed interface FieldRule {
    object Text : FieldRule
    data class Enum(val values: Set<String>) : FieldRule
    data class Num(val min: Double? = null, val max: Double? = null) : FieldRule
    object Code6 : FieldRule

    /** allowed == null 表示只校验 YYYY-MM-DD 格式，不做集合校验 */
    data class DateIn(val allowed: Set<String>?) : FieldRule

    data class TextList(val max: Int, val min: Int = 1) : FieldRule

    /**
     * 对象数组。兼容元素为纯字符串：会把字符串塞进 required 的第一个 key。
     * 例：signals 兼容 ["纯文本"] -> [{text:"纯文本"}]
     */
    data class ObjList(
        val required: Map<String, FieldRule>,
        val optional: Map<String, FieldRule> = emptyMap(),
        val min: Int = 1,
        val max: Int = 8,
    ) : FieldRule
}

data class CardSchema(
    val type: String,
    val required: Map<String, FieldRule>,
    val optional: Map<String, FieldRule> = emptyMap(),
)

class ValidateReport {
    var droppedCards: Int = 0
    val strippedFields: MutableList<String> = mutableListOf()
    val notes: MutableList<String> = mutableListOf()

    fun hasIssue(): Boolean = droppedCards > 0 || strippedFields.isNotEmpty()

    fun stripRate(field: String, total: Int): Double =
        if (total == 0) 0.0 else strippedFields.count { it == field }.toDouble() / total

    override fun toString(): String =
        "ValidateReport(dropped=$droppedCards, stripped=$strippedFields, notes=$notes)"
}

private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

@Suppress("UNCHECKED_CAST")
internal fun anyToStringMap(v: Any?): Map<String, Any?>? =
    (v as? Map<*, *>)?.entries?.associate { (k, value) -> k.toString() to value }

fun coerce(value: Any?, rule: FieldRule): Any? = when (rule) {
    is FieldRule.Text ->
        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (value as? Number)?.toString()

    is FieldRule.Enum ->
        (value as? String)?.trim()?.takeIf { it in rule.values }

    is FieldRule.Num -> {
        val d: Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim()
                .replace(",", "")
                .removeSuffix("元")
                .removePrefix("约")
                .toDoubleOrNull()
            else -> null
        }
        d?.takeIf { it.isFinite() }
            ?.takeIf { rule.min == null || it >= rule.min }
            ?.takeIf { rule.max == null || it <= rule.max }
    }

    is FieldRule.Code6 ->
        (value as? String)?.trim()?.takeIf { it.length == 6 && it.all(Char::isDigit) }

    is FieldRule.DateIn ->
        (value as? String)?.trim()
            ?.takeIf { DATE_REGEX.matches(it) }
            ?.takeIf { rule.allowed == null || it in rule.allowed }

    is FieldRule.TextList ->
        (value as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            ?.take(rule.max)
            ?.takeIf { it.size >= rule.min }

    is FieldRule.ObjList -> {
        val items = (value as? List<*>)?.mapNotNull { item ->
            val map: Map<String, Any?> = when (item) {
                is Map<*, *> -> anyToStringMap(item) ?: return@mapNotNull null
                is String -> mapOf(rule.required.keys.first() to item)
                else -> return@mapNotNull null
            }
            validateObject(map, rule.required, rule.optional)
        } ?: emptyList()
        items.take(rule.max).takeIf { it.size >= rule.min }
    }
}

/**
 * @return null 表示必填字段缺失，对象作废
 */
fun validateObject(
    src: Map<String, Any?>,
    required: Map<String, FieldRule>,
    optional: Map<String, FieldRule>,
    strippedOut: MutableList<String>? = null,
    prefix: String = "",
): Map<String, Any?>? {
    val out = LinkedHashMap<String, Any?>()
    for ((k, rule) in required) {
        out[k] = coerce(src[k], rule) ?: return null
    }
    for ((k, rule) in optional) {
        val v = coerce(src[k], rule)
        if (v != null) out[k] = v
        else if (src.containsKey(k) && src[k] != null) strippedOut?.add("$prefix$k")
    }
    return out
}

fun validateCards(
    raw: List<Any?>?,
    registry: Map<String, CardSchema>,
    report: ValidateReport,
    maxCards: Int = 8,
): List<Map<String, Any?>> {
    if (raw == null) return emptyList()
    val out = mutableListOf<Map<String, Any?>>()
    for (item in raw) {
        if (out.size >= maxCards) {
            report.notes += "超出 maxCards=$maxCards，后续卡片被截断"
            break
        }
        val map = anyToStringMap(item)
        val type = map?.get("type") as? String
        val schema = type?.let { registry[it] }
        if (map == null || schema == null) {
            report.droppedCards++
            report.notes += "未知卡片类型: ${type ?: "<null>"}"
            continue
        }
        val body = validateObject(
            map, schema.required, schema.optional,
            strippedOut = report.strippedFields, prefix = "$type.",
        )
        if (body == null) {
            report.droppedCards++
            report.notes += "卡片 $type 必填字段缺失: ${schema.required.keys.filter { coerce(map[it], schema.required[it]!!) == null }}"
            continue
        }
        out += LinkedHashMap<String, Any?>().apply {
            put("type", type)
            putAll(body)
        }
    }
    return out
}
