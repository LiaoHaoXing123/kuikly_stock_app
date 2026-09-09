package com.kuikly.stock.ai.protocol

/**
 * L2/L3 降级时从旧字段合成 verdict，保证 P0 UI 不空窗。
 */
object VerdictSynthesizer {

    private val BULL = listOf("看多", "偏多", "上涨", "突破", "金叉", "买入", "多头", "走强", "反弹", "放量上攻")
    private val BEAR = listOf("看空", "偏空", "下跌", "跌破", "死叉", "卖出", "空头", "走弱", "回落", "破位")

    data class Result(
        val bias: String,
        val oneLiner: String,
        val confidence: String = "低",
        val supportValue: Double? = null,
        val resistanceValue: Double? = null,
        val targetValue: Double? = null,
        val stopLossValue: Double? = null,
    )

    /**
     * @param analysis 旧协议宽松 JSON（AIAnalysisData.analysis）
     * @param numericLevel 复用 DeepSeekApi 里现有的字符串抠数字函数
     */
    fun fromLegacy(
        analysis: Map<String, Any?>,
        numericLevel: (String?) -> Double?,
    ): Result {
        val suggestion = analysis["suggestion"]?.toString().orEmpty()
        val trend = analysis["trend"]?.toString().orEmpty()
        val summary = analysis["summary"]?.toString().orEmpty()
        val corpus = "$suggestion $trend $summary"

        val bullScore = BULL.count { corpus.contains(it) } + if (suggestion.contains("买入")) 2 else 0
        val bearScore = BEAR.count { corpus.contains(it) } + if (suggestion.contains("卖出")) 2 else 0
        val bias = when {
            bullScore > bearScore -> "偏多"
            bearScore > bullScore -> "偏空"
            else -> "中性"
        }

        val oneLiner = (summary.ifBlank { trend }).trim()
            .replace("\n", " ")
            .let { if (it.length > 40) it.take(39) + "…" else it }
            .ifBlank { "AI 已完成基础研判，详见下方卡片" }

        return Result(
            bias = bias,
            oneLiner = oneLiner,
            confidence = "低",
            supportValue = numericLevel(analysis["support_price"]?.toString()),
            resistanceValue = numericLevel(analysis["resistance_price"]?.toString()),
            targetValue = numericLevel(analysis["target_price"]?.toString()),
            stopLossValue = numericLevel(analysis["stop_loss"]?.toString()),
        )
    }
}
