// AI 复盘引擎：把「分析历史里的 verdict」与「其后真实日 K」对照，纯本地程序计算，
// 不调用任何 AI 接口。它的产出是给用户看的战绩卡，所以判定规则必须保守、可解释：
// 方向判定留 1% 的容错带，中性判定要求波动收敛在 ±3% 内，宁可判「偏差」也不送分。

package com.kuikly.stock.data

import com.kuikly.stock.ai.protocol.VerdictSynthesizer
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.pages.AIAnalysisData
import com.kuikly.stock.pages.KLineDataItem
import kotlin.math.abs

/** 单条历史分析的复盘结果。价位验证字段为 null 表示该维度无法判定（没给价位/还没走出数据）。 */
data class AiReviewEntry(
    val id: String,
    val generatedAt: Long,
    val dataDate: String,
    val bias: String,
    val confidence: String,
    val horizon: String,
    val support: Double?,
    val resistance: Double?,
    val target: Double?,
    val stopLoss: Double?,
    /** 实际基准日：dataDate 当日或其后第一个交易日（分析可能基于当日盘中快照）。 */
    val baseDate: String?,
    val basePrice: Double?,
    val elapsedDays: Int,
    val windowDays: Int,
    val latestDate: String?,
    /** 基准日收盘 → 最新收盘 的涨跌 %。基准后尚无交易日时为 null。 */
    val returnPct: Double?,
    val supportHeld: Boolean?,
    val resistanceTouched: Boolean?,
    val targetReached: Boolean?,
    val stopHit: Boolean?,
    val directionHit: Boolean?,
) {
    val expired: Boolean get() = elapsedDays >= windowDays

    val status: String
        get() = when {
            basePrice == null -> "无法复盘"
            elapsedDays <= 0 -> "待验证"
            else -> if (expired) "已到期" else "进行中"
        }
}

data class AiReviewSummary(
    val entries: List<AiReviewEntry>,
    /** 已走出行情、可判方向的条数。 */
    val evaluatedCount: Int,
    val directionHitCount: Int,
) {
    val hitRatePct: Int?
        get() = if (evaluatedCount > 0) directionHitCount * 100 / evaluatedCount else null
}

internal object AiReviewEngine {

    private const val BULL_THRESHOLD = 1.0    // 偏多判定需至少 +1%
    private const val BEAR_THRESHOLD = -1.0   // 偏空判定需至少 -1%
    private const val NEUTRAL_BAND = 3.0      // 中性判定需收敛在 ±3% 内

    /**
     * 统一的 verdict 还原：新记录直接用落盘的 v2 verdict；旧记录（协议 v1/离线模板/
     * verdict 持久化之前的历史）从 analysis 文本字段合成。详情页观点条与复盘共用。
     */
    fun verdictOf(result: AIAnalysisData): AIVerdict? {
        result.verdict?.let { return it }
        return runCatching {
            AIVerdict.fromSynth(
                VerdictSynthesizer.fromLegacy(result.analysis) { DeepSeekApi.numericLevel(it) }
            )
        }.getOrNull()
    }

    fun review(records: List<AnalysisSnapshot>, kline: List<KLineDataItem>): AiReviewSummary {
        if (records.isEmpty() || kline.isEmpty()) return AiReviewSummary(emptyList(), 0, 0)
        val entries = records.mapNotNull { rec ->
            val verdict = verdictOf(rec.result) ?: return@mapNotNull null
            reviewOne(rec, verdict, kline)
        }
        val evaluated = entries.filter { it.directionHit != null }
        return AiReviewSummary(
            entries = entries,
            evaluatedCount = evaluated.size,
            directionHitCount = evaluated.count { it.directionHit == true },
        )
    }

    private fun reviewOne(rec: AnalysisSnapshot, verdict: AIVerdict, kline: List<KLineDataItem>): AiReviewEntry {
        val dataDate = rec.result.dataDate
        val baseIdx = if (dataDate.isBlank()) -1 else kline.indexOfFirst { it.tradeDate >= dataDate }
        val base = kline.getOrNull(baseIdx)
        val after = if (baseIdx < 0) emptyList() else kline.drop(baseIdx + 1)
        val latest = kline.lastOrNull()
        val elapsed = if (baseIdx < 0) 0 else kline.lastIndex - baseIdx
        val basePrice = base?.close
        val returnPct = if (basePrice != null && basePrice != 0.0 && after.isNotEmpty()) {
            (latest!!.close - basePrice) / basePrice * 100.0
        } else null
        return AiReviewEntry(
            id = rec.id,
            generatedAt = rec.result.generatedAt,
            dataDate = dataDate,
            bias = verdict.bias,
            confidence = verdict.confidence,
            horizon = verdict.horizon,
            support = verdict.supportValue,
            resistance = verdict.resistanceValue,
            target = verdict.targetValue,
            stopLoss = verdict.stopLossValue,
            baseDate = base?.tradeDate,
            basePrice = basePrice,
            elapsedDays = elapsed,
            windowDays = windowDays(verdict.horizon),
            latestDate = latest?.tradeDate,
            returnPct = returnPct,
            supportHeld = verdict.supportValue
                ?.takeIf { after.isNotEmpty() }
                ?.let { s -> after.all { it.low >= s } },
            resistanceTouched = verdict.resistanceValue?.let { r -> after.any { it.high >= r } },
            targetReached = verdict.targetValue?.let { t -> after.any { it.high >= t } },
            stopHit = verdict.stopLossValue?.let { s -> after.any { it.low <= s } },
            directionHit = directionHit(verdict.bias, returnPct),
        )
    }

    private fun directionHit(bias: String, returnPct: Double?): Boolean? {
        if (returnPct == null) return null
        return when (bias) {
            "偏多" -> returnPct >= BULL_THRESHOLD
            "偏空" -> returnPct <= BEAR_THRESHOLD
            else -> abs(returnPct) <= NEUTRAL_BAND
        }
    }

    /** 评估窗口（交易日）：与 verdict.horizon 对齐，短线 10 / 中线 20 / 长线 60。 */
    private fun windowDays(horizon: String): Int = when {
        horizon.contains("中") -> 20
        horizon.contains("长") -> 60
        else -> 10
    }
}
