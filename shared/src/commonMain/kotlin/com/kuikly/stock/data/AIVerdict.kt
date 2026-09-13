package com.kuikly.stock.data

import com.tencent.kuikly.core.base.Color
import com.kuikly.stock.ai.protocol.VerdictSynthesizer

data class AIVerdict(
    val bias: String,
    val oneLiner: String,
    val confidence: String = "中",
    val horizon: String = "短线",
    val supportValue: Double? = null,
    val resistanceValue: Double? = null,
    val targetValue: Double? = null,
    val stopLossValue: Double? = null,
) {

    val colorValue: Long
        get() = when (bias) {
            "偏多" -> StockColors.UP
            "偏空" -> StockColors.DOWN
            else -> StockColors.FLAT
        }

    val color: Color get() = Color(colorValue)

    val tintColor: Color get() = Color((colorValue and 0x00FFFFFF) or 0x1A000000)

    val chipColor: Color get() = Color((colorValue and 0x00FFFFFF) or 0x22000000)

    companion object {
        fun from(m: Map<String, Any?>): AIVerdict = AIVerdict(
            bias = m["bias"] as? String ?: "中性",
            oneLiner = m["one_liner"] as? String ?: "",
            confidence = m["confidence"] as? String ?: "中",
            horizon = m["horizon"] as? String ?: "短线",
            supportValue = (m["support_value"] as? Number)?.toDouble(),
            resistanceValue = (m["resistance_value"] as? Number)?.toDouble(),
            targetValue = (m["target_value"] as? Number)?.toDouble(),
            stopLossValue = (m["stop_loss_value"] as? Number)?.toDouble(),
        )

        fun fromSynth(r: VerdictSynthesizer.Result): AIVerdict = AIVerdict(
            bias = r.bias,
            oneLiner = r.oneLiner,
            confidence = r.confidence,
            supportValue = r.supportValue,
            resistanceValue = r.resistanceValue,
            targetValue = r.targetValue,
            stopLossValue = r.stopLossValue,
        )
    }
}
