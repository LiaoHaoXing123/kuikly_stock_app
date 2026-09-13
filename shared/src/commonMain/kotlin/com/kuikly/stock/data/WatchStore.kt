package com.kuikly.stock.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal data class WatchHolding(
    val code: String,
    val name: String,
    val shares: Double = 0.0,
    val cost: Double = 0.0,
    val startDate: String = "",
)

internal data class PriceAlertRule(
    val code: String,
    val name: String,
    val type: Int,
    val threshold: Double,
    val enabled: Boolean = true
) {
    val identity: String get() = "${code}_${type}_${threshold}"
}

internal object WatchStore : WatchRepository(::appPrefsGet, ::appPrefsSet)

internal open class WatchRepository(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit
) {

    private val KEY_WATCH = "watch_v1"
    private val KEY_ALERT = "alert_v1"

    fun list(): List<WatchHolding> = parseWatch(read(KEY_WATCH))

    fun isWatched(code: String): Boolean = list().any { it.code == code }

    fun find(code: String): WatchHolding? = list().firstOrNull { it.code == code }

    fun save(items: List<WatchHolding>) {
        write(KEY_WATCH, encodeWatch(items))
    }

    fun toggle(code: String, name: String): Boolean {
        val cur = list().toMutableList()
        val idx = cur.indexOfFirst { it.code == code }
        val added = if (idx >= 0) {
            cur.removeAt(idx)
            removeAlertsForStock(code)
            false
        } else {
            cur.add(WatchHolding(code, name))
            true
        }
        save(cur)
        return added
    }

    fun updateHolding(item: WatchHolding) {
        val cur = list().toMutableList()
        val idx = cur.indexOfFirst { it.code == item.code }
        if (idx >= 0) cur[idx] = item else cur.add(item)
        save(cur)
    }

    fun remove(code: String) {
        save(list().filterNot { it.code == code })
        removeAlertsForStock(code)
    }

    fun clearHolding(code: String) {
        find(code)?.let { updateHolding(it.copy(shares = 0.0, cost = 0.0, startDate = "")) }
    }

    fun alerts(): List<PriceAlertRule> = parseAlerts(read(KEY_ALERT))

    fun alertsOf(code: String): List<PriceAlertRule> = alerts().filter { it.code == code }

    fun saveAlerts(items: List<PriceAlertRule>) {
        write(KEY_ALERT, encodeAlerts(items))
    }

    fun upsertAlert(rule: PriceAlertRule): Boolean {
        if (rule.code.isBlank() || !rule.threshold.isFinite() || rule.threshold <= 0.0 || rule.type !in 0..3) return false
        val cur = alerts().filterNot { it.identity == rule.identity }.toMutableList()
        cur.add(rule)
        return runCatching {
            saveAlerts(cur)
            if (alerts().none { it == rule }) return@runCatching false
            // Every rule needs a visible management entry, including rules created from detail/chat.
            if (!isWatched(rule.code)) updateHolding(WatchHolding(rule.code, rule.name))
            isWatched(rule.code)
        }.getOrDefault(false)
    }

    fun removeAlertsForStock(code: String) {
        saveAlerts(alerts().filterNot { it.code == code })
    }

    fun removeAlert(rule: PriceAlertRule): Boolean = runCatching {
        saveAlerts(alerts().filterNot { it.identity == rule.identity })
        alerts().none { it.identity == rule.identity }
    }.getOrDefault(false)

    private fun encodeWatch(items: List<WatchHolding>): String {
        val arr = JSONArray()
        for (h in items) {
            arr.put(JSONObject()
                .put("code", h.code)
                .put("name", h.name)
                .put("shares", h.shares)
                .put("cost", h.cost)
                .put("startDate", h.startDate))
        }
        return JSONObject().put("items", arr).toString()
    }

    private fun parseWatch(raw: String?): List<WatchHolding> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(WatchHolding(
                        code = o.optString("code", ""),
                        name = o.optString("name", ""),
                        shares = o.optDouble("shares", 0.0),
                        cost = o.optDouble("cost", 0.0),
                        startDate = o.optString("startDate", ""),
                    ))
                }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun encodeAlerts(items: List<PriceAlertRule>): String {
        val arr = JSONArray()
        for (r in items) {
            arr.put(JSONObject()
                .put("code", r.code)
                .put("name", r.name)
                .put("type", r.type)
                .put("threshold", r.threshold)
                .put("enabled", r.enabled))
        }
        return JSONObject().put("items", arr).toString()
    }

    private fun parseAlerts(raw: String?): List<PriceAlertRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(PriceAlertRule(
                        code = o.optString("code", ""),
                        name = o.optString("name", ""),
                        type = o.optInt("type", 0),
                        threshold = o.optDouble("threshold", 0.0),
                        enabled = o.optBoolean("enabled", true)
                    ))
                }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
