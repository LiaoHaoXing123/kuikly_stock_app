package com.kuikly.stock.ai.protocol

/**
 * 聊天协议 v1 卡片白名单声明，迁移到共享校验器。
 * 行为与现有 validCard 等价，多了字段级降级。
 */
object ChatProtocolV1 {
    const val VERSION = 1
    const val MAX_CARDS = 8
    const val MAX_SUGGESTIONS = 3
    const val MAX_TEXT = 12_000

    private val PRICE = FieldRule.Num(min = 0.0001)
    private val ANY_DATE = FieldRule.DateIn(null)

    private val quoteCard = mapOf(
        "code" to FieldRule.Code6,
        "name" to FieldRule.Text,
        "price" to PRICE,
        "change_percent" to FieldRule.Text, // 协议定义为字符串，如 "+0.40%"、"1%"
    )

    val registry: Map<String, CardSchema> = listOf(
        CardSchema("stock_card", required = quoteCard, strict = true),
        CardSchema("index_card", required = quoteCard, strict = true),
        CardSchema(
            type = "conclusion_card",
            required = mapOf(
                "code" to FieldRule.Code6,
                "name" to FieldRule.Text,
                "bias" to FieldRule.Enum(setOf("偏强", "偏弱", "中性", "偏多", "偏空")),
                "one_liner" to FieldRule.Text,
            ),
            optional = mapOf(
                "bias_note" to FieldRule.Text,
                "change_percent" to FieldRule.Text,
                "support" to FieldRule.Text,
                "resistance" to FieldRule.Text,
                "support_value" to PRICE,
                "resistance_value" to PRICE,
                "data_date" to ANY_DATE,
                "indicator_date" to ANY_DATE,
                "action" to FieldRule.Text,
                "footnote" to FieldRule.Text,
                "signals" to FieldRule.TextList(max = 5),
            ),
            strict = true,
        ),
        CardSchema("signal_card", required = mapOf("content" to FieldRule.Text), strict = true),
        CardSchema("risk_card", required = mapOf("content" to FieldRule.Text), strict = true),
        CardSchema(
            type = "chart_card",
            required = mapOf(
                "title" to FieldRule.Text,
                "code" to FieldRule.Code6,
                "chart_type" to FieldRule.Enum(setOf("line", "bar")),
                "data" to FieldRule.ObjList(
                    required = mapOf("label" to FieldRule.Text, "value" to FieldRule.Num()),
                    min = 2, max = 120,
                ),
            ),
            strict = true,
        ),
    ).associateBy { it.type }
}
