package com.kuikly.stock.ai.config

import com.kuikly.stock.data.appPrefsGet
import com.kuikly.stock.data.appPrefsSet

internal object AiProfileStore {
    private const val KEY_PROFILES = "ai_profiles_v1"
    private const val KEY_ACTIVE = "ai_profile_active_v1"

    fun ensureSeeded() {
        if (decodeAiProfiles(appPrefsGet(KEY_PROFILES)).isEmpty()) {
            val presets = defaultAiProfiles()
            appPrefsSet(KEY_PROFILES, encodeAiProfiles(presets))
            appPrefsSet(KEY_ACTIVE, presets.first().id)
        }
    }

    fun profiles(): List<AiProviderProfile> {
        ensureSeeded()
        return decodeAiProfiles(appPrefsGet(KEY_PROFILES))
    }

    fun active(): AiProviderProfile {
        val profiles = profiles()
        val activeId = appPrefsGet(KEY_ACTIVE)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first().also {
            appPrefsSet(KEY_ACTIVE, it.id)
        }
    }

    fun setActive(id: String) {
        require(profiles().any { it.id == id }) { "API 档案不存在" }
        appPrefsSet(KEY_ACTIVE, id)
    }

    fun saveProfile(profile: AiProviderProfile) {
        require(profile.id.isNotBlank()) { "API 档案 ID 不能为空" }
        require(profile.name.isNotBlank()) { "请输入配置名称" }
        require(validateBaseUrl(profile.baseUrl) == null) { validateBaseUrl(profile.baseUrl) ?: "Base URL 无效" }
        require(profile.model.isNotBlank()) { "请输入模型 ID" }
        val current = profiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current.add(profile)
        appPrefsSet(KEY_PROFILES, encodeAiProfiles(current))
    }

    fun deleteProfile(id: String): Boolean {
        val current = profiles()
        if (current.size <= 1 || current.none { it.id == id }) return false
        val nextActive = selectAfterRemoval(current.map { it.id }, active().id, id) ?: return false
        appPrefsSet(KEY_PROFILES, encodeAiProfiles(current.filterNot { it.id == id }))
        appPrefsSet(KEY_ACTIVE, nextActive)
        return true
    }
}
