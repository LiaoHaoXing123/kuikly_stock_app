package com.kuikly.stock.data

import com.kuikly.stock.pages.AIAnalysisData
import kotlinx.serialization.json.*

internal data class AnalysisSnapshot(val id: String, val kind: String, val result: AIAnalysisData)

internal class AnalysisHistoryStore(
    private val read: (String) -> String? = ::appPrefsGet,
    private val write: (String, String) -> Unit = ::appPrefsSet,
) {
    private val key = "detail_analysis_v1"

    fun list(kind: String, code: String): List<AnalysisSnapshot> = all()
        .filter { it.kind == kind && it.result.code == code }

    fun save(kind: String, result: AIAnalysisData): Boolean = runCatching {
        require(kind in listOf("stock", "index") && result.code.isNotBlank())
        val previous = all()
        val time = result.generatedAt.takeIf { it > 0 } ?: nowMillis()
        var id = "$kind:${result.code}:$time"
        var suffix = 0
        while (previous.any { it.id == id }) id = "$kind:${result.code}:$time:${++suffix}"
        val record = AnalysisSnapshot(id, kind, result.copy(generatedAt = time))
        val sameSymbol = previous.filter { it.kind == kind && it.result.code == result.code }.take(19)
        val otherSymbols = previous.filterNot { it.kind == kind && it.result.code == result.code }
        val records = (listOf(record) + sameSymbol + otherSymbols).take(100)
        val payload = JsonArray(records.map(::encode)).toString()
        write(key, payload)
        read(key) == payload
    }.getOrDefault(false)

    fun delete(id: String): Boolean = runCatching {
        val payload = JsonArray(all().filterNot { it.id == id }.map(::encode)).toString()
        write(key, payload)
        read(key) == payload
    }.getOrDefault(false)

    private fun all(): List<AnalysisSnapshot> = runCatching {
        val array = Json.parseToJsonElement(read(key) ?: "[]") as? JsonArray ?: return emptyList()
        array.mapNotNull { element -> runCatching { decode(element.jsonObject) }.getOrNull() }.take(100)
    }.getOrDefault(emptyList())

    private fun encode(record: AnalysisSnapshot): JsonObject = buildJsonObject {
        put("id", record.id); put("kind", record.kind)
        val r = record.result
        put("code", r.code); put("name", r.name)
        put("source", r.source); put("generatedAt", r.generatedAt); put("dataDate", r.dataDate)
        put("analysis", toJson(r.analysis)); put("cards", toJson(r.cards))

        r.verdict?.let { put("verdict", toJson(verdictMap(it))) }
    }

    private fun verdictMap(v: AIVerdict): Map<String, Any?> = mapOf(
        "bias" to v.bias,
        "one_liner" to v.oneLiner,
        "confidence" to v.confidence,
        "horizon" to v.horizon,
        "support_value" to v.supportValue,
        "resistance_value" to v.resistanceValue,
        "target_value" to v.targetValue,
        "stop_loss_value" to v.stopLossValue,
    )

    private fun decode(o: JsonObject): AnalysisSnapshot {
        fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        val code = text("code")
        require(code.isNotBlank() && text("id").isNotBlank() && text("kind") in listOf("stock", "index"))
        val cards = (o["cards"] as? JsonArray ?: error("missing cards")).map { card ->
            card.jsonObject.mapValues { fromJson(it.value) }
        }
        val verdict = (o["verdict"] as? JsonObject)
            ?.mapValues { fromJson(it.value) }
            ?.let { AIVerdict.from(it) }
        return AnalysisSnapshot(text("id"), text("kind"), AIAnalysisData(
            code, text("name"), o["analysis"]!!.jsonObject.mapValues { fromJson(it.value) }, cards,
            source = text("source"), generatedAt = o["generatedAt"]!!.jsonPrimitive.long,
            dataDate = text("dataDate"), verdict = verdict,
        ))
    }
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> if (value.toDouble().isFinite()) JsonPrimitive(value) else JsonNull
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
    is List<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}

private fun fromJson(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.mapValues { fromJson(it.value) }
    is JsonArray -> value.map(::fromJson)
    is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.doubleOrNull ?: value.content
}
