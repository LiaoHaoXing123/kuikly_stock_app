package com.kuikly.stock.ai.protocol

object DetailProtocolV2 {
    const val VERSION = 2
    const val MAX_CARDS = 6

    private val PRICE = FieldRule.Num(min = 0.0001)

    val VERDICT_REQUIRED: Map<String, FieldRule> = mapOf(
        "bias" to FieldRule.Enum(setOf("偏多", "偏空", "中性")),
        "one_liner" to FieldRule.Text,
    )

    val VERDICT_OPTIONAL: Map<String, FieldRule> = mapOf(
        "confidence" to FieldRule.Enum(setOf("高", "中", "低")),
        "horizon" to FieldRule.Enum(setOf("短线", "中线", "长线")),
        "support_value" to PRICE,
        "resistance_value" to PRICE,
        "target_value" to PRICE,
        "stop_loss_value" to PRICE,
    )

    /**
     * @param klineDates 当前图表真实存在的交易日集合，用于日期防幻觉。
     *                   为空集合时退化为只校验格式（离线/无K线场景）。
     */
    fun registry(klineDates: Set<String>): Map<String, CardSchema> {
        val date = FieldRule.DateIn(klineDates.takeIf { it.isNotEmpty() })
        return listOf(
            CardSchema(
                type = "trend_card",
                required = mapOf("title" to FieldRule.Text, "content" to FieldRule.Text),
                optional = mapOf("start_date" to date, "end_date" to date),
            ),
            CardSchema(
                type = "signal_card",
                required = mapOf(
                    "title" to FieldRule.Text,
                    "signals" to FieldRule.ObjList(
                        required = mapOf("text" to FieldRule.Text),
                        optional = mapOf(
                            "direction" to FieldRule.Enum(setOf("多", "空", "中性")),
                            "start_date" to date,
                            "end_date" to date,
                            "ref_price" to PRICE,
                        ),
                        min = 1, max = 6,
                    ),
                ),
            ),
            CardSchema(
                type = "level_card",
                required = mapOf("action" to FieldRule.Enum(setOf("买入", "卖出", "持有", "观望"))),
                optional = mapOf(
                    "target_value" to PRICE,
                    "stop_loss_value" to PRICE,
                    "support_value" to PRICE,
                    "resistance_value" to PRICE,
                    "data_date" to date,
                    "indicator_date" to date,
                ),
            ),
            CardSchema(
                type = "risk_card",
                required = mapOf(
                    "risk_level" to FieldRule.Enum(setOf("低", "中", "高")),
                    "risks" to FieldRule.TextList(max = 5),
                ),
            ),
            CardSchema(
                type = "evidence_card",
                required = mapOf(
                    "items" to FieldRule.ObjList(
                        required = mapOf("date" to date, "fact" to FieldRule.Text),
                        optional = mapOf("metric" to FieldRule.Text, "value" to FieldRule.Text),
                        min = 1, max = 6,
                    ),
                ),
            ),
            CardSchema(
                type = "summary_card",
                required = mapOf("summary" to FieldRule.Text),
            ),
        ).associateBy { it.type }
    }

    /** 注入 Prompt 的协议说明 */
    val PROMPT: String = """
只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字。

{
  "version": 2,
  "verdict": {
    "bias": "偏多|偏空|中性",
    "confidence": "高|中|低",
    "one_liner": "不超过40字的一句话结论",
    "horizon": "短线|中线|长线",
    "support_value": 数字,
    "resistance_value": 数字,
    "target_value": 数字,
    "stop_loss_value": 数字
  },
  "cards": [
    {"type":"trend_card","title":"趋势研判","content":"...","start_date":"YYYY-MM-DD","end_date":"YYYY-MM-DD"},
    {"type":"signal_card","title":"技术信号","signals":[
      {"text":"信号描述","direction":"多|空|中性","start_date":"YYYY-MM-DD","end_date":"YYYY-MM-DD","ref_price":数字}
    ]},
    {"type":"level_card","action":"买入|卖出|持有|观望","target_value":数字,"stop_loss_value":数字,"support_value":数字,"resistance_value":数字,"data_date":"YYYY-MM-DD"},
    {"type":"risk_card","risk_level":"低|中|高","risks":["风险1","风险2"]},
    {"type":"evidence_card","items":[{"date":"YYYY-MM-DD","fact":"当日事实","metric":"指标名","value":"指标值"}]},
    {"type":"summary_card","summary":"总结"}
  ]
}

硬性约束：
1. 所有价格字段必须是 JSON number，禁止字符串、禁止带"元"或"约"。
2. 所有日期字段必须是下方【K线数据】中真实出现的交易日，禁止编造、禁止使用未来日期。
3. signals 每条尽量带 start_date/end_date 指向该信号在K线上形成的区间；确实无法定位时省略日期字段，不得填假日期。
4. cards 最多 6 张且每种类型最多 1 张；signals 最多 6 条；risks 最多 5 条；evidence items 最多 6 条。
5. 不得输出上述未定义的字段。
""".trimIndent()
}
