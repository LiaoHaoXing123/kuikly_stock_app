package com.kuikly.stock.ai.config

internal data class AiRequestConfig(
    val profileId: String,
    val providerName: String,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val toolsEnabled: Boolean,
)

internal data class AiConnectionResult(
    val success: Boolean,
    val providerName: String,
    val model: String,
    val elapsedMs: Long,
    val message: String,
)

internal class AiConfigurationException(message: String) : IllegalStateException(message)

internal class AiProviderException(
    val status: Int,
    message: String,
) : IllegalStateException(message)

internal fun resolveAiRequestConfig(
    profile: AiProviderProfile,
    apiKey: String?,
): AiRequestConfig {
    validateBaseUrl(profile.baseUrl)?.let { throw AiConfigurationException(it) }
    if (profile.model.isBlank()) throw AiConfigurationException("请输入模型 ID")
    if (apiKey.isNullOrBlank()) throw AiConfigurationException("请先在 API 配置中填写密钥")
    return AiRequestConfig(
        profileId = profile.id,
        providerName = profile.name,
        endpoint = profile.chatCompletionsUrl(),
        model = profile.model.trim(),
        apiKey = apiKey,
        toolsEnabled = profile.toolsEnabled,
    )
}

internal object AiRuntimeConfig {
    fun current(): AiRequestConfig {
        val profile = AiProfileStore.active()
        return resolveAiRequestConfig(profile, SecureSecretStore.get(profile.id))
    }

    fun isConfigured(): Boolean = try {
        current()
        true
    } catch (_: Throwable) {
        false
    }

    fun activeProfile(): AiProviderProfile = AiProfileStore.active()
}
