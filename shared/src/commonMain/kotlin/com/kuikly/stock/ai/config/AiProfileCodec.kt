package com.kuikly.stock.ai.config

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal fun defaultAiProfiles(now: Long = System.currentTimeMillis()): List<AiProviderProfile> = listOf(
    AiProviderProfile(
        id = "agentrouter",
        name = "AgentRouter",
        baseUrl = "https://co.agentrouter.org/v1",
        model = "deepseek-v4-flash",
        toolsEnabled = true,
        createdAt = now,
        updatedAt = now,
    ),
    AiProviderProfile(
        id = "deepseek",
        name = "DeepSeek 官方",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        toolsEnabled = true,
        createdAt = now,
        updatedAt = now,
    ),
)

internal fun encodeAiProfiles(profiles: List<AiProviderProfile>): String {
    val items = JSONArray()
    profiles.forEach { profile ->
        items.put(
            JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("base_url", profile.baseUrl)
                .put("model", profile.model)
                .put("tools_enabled", profile.toolsEnabled)
                .put("created_at", profile.createdAt)
                .put("updated_at", profile.updatedAt)
        )
    }
    return JSONObject().put("items", items).toString()
}

internal fun decodeAiProfiles(raw: String?): List<AiProviderProfile> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val items = JSONObject(raw).optJSONArray("items") ?: return emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id", "").trim()
                val name = item.optString("name", "").trim()
                val baseUrl = item.optString("base_url", "").trim()
                val model = item.optString("model", "").trim()
                if (id.isEmpty() || name.isEmpty() || baseUrl.isEmpty() || model.isEmpty()) continue
                add(
                    AiProviderProfile(
                        id = id,
                        name = name,
                        baseUrl = baseUrl,
                        model = model,
                        toolsEnabled = item.optBoolean("tools_enabled", true),
                        createdAt = item.optLong("created_at", 0L),
                        updatedAt = item.optLong("updated_at", 0L),
                    )
                )
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun selectAfterRemoval(
    profileIds: List<String>,
    activeId: String?,
    removedId: String,
): String? {
    val remaining = profileIds.filterNot { it == removedId }
    if (remaining.isEmpty()) return null
    return if (activeId != removedId && activeId in remaining) activeId else remaining.first()
}
