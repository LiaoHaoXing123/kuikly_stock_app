package com.kuikly.stock.data

import com.kuikly.stock.ai.protocol.VerdictSynthesizer
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.pages.AIAnalysisData
import com.kuikly.stock.pages.KLineDataItem
import kotlin.math.abs

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

    val baseDate: String?,
    val basePrice: Double?,
    val elapsedDays: Int,
    val windowDays: Int,
    val latestDate: String?,

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

    val evaluatedCount: Int,
    val directionHitCount: Int,
) {
    val hitRatePct: Int?
        get() = if (evaluatedCount > 0) directionHitCount * 100 / evaluatedCount else null
}

internal object AiReviewEngine {

    private const val BULL_THRESHOLD = 1.0
    private const val BEAR_THRESHOLD = -1.0
    private const val NEUTRAL_BAND = 3.0

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

    private fun windowDays(horizon: String): Int = when {
        horizon.contains("中") -> 20
        horizon.contains("长") -> 60
        else -> 10
    }
}
