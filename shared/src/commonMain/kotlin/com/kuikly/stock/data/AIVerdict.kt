package com.kuikly.stock.data

import com.tencent.kuikly.core.base.Color
import com.kuikly.stock.ai.protocol.VerdictSynthesizer

/**
 * AI 顶层观点结论（用于 K 线顶部观点条、行情区注解 Chip 等独立嵌入位置）
 */
data class AIVerdict(
    val bias: String,                // 偏多 / 偏空 / 中性
    val oneLiner: String,
    val confidence: String = "中",   // 高 / 中 / 低
    val horizon: String = "短线",
    val supportValue: Double? = null,
    val resistanceValue: Double? = null,
    val targetValue: Double? = null,
    val stopLossValue: Double? = null,
) {
    /** A股配色：涨红跌绿 */
    val colorValue: Long
        get() = when (bias) {
            "偏多" -> 0xFFE64545
            "偏空" -> 0xFF17A67A
            else -> 0xFF8A9099
        }

    val color: Color get() = Color(colorValue)

    /** 10% 透明度背景色 */
    val tintColor: Color get() = Color((colorValue and 0x00FFFFFF) or 0x1A000000)

    /** 13% 透明度背景色 */
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
