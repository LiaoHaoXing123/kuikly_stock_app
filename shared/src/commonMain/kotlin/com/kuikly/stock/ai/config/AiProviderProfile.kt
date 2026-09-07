package com.kuikly.stock.ai.config

internal data class AiProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val toolsEnabled: Boolean,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun chatCompletionsUrl(): String = baseUrl.trim().trimEnd('/') + "/chat/completions"
}

internal fun validateBaseUrl(raw: String): String? {
    val url = raw.trim()
    if (url.isEmpty()) return "请输入 Base URL"
    if (url.startsWith("https://")) return null
    if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) return null
    return "生产服务必须使用 HTTPS"
}

internal fun maskApiKey(key: String): String =
    if (key.isBlank()) "未配置" else "•••• ·" + key.takeLast(4)

internal fun providerErrorMessage(status: Int): String = when (status) {
    401, 403 -> "API Key 无效或没有权限"
    404 -> "Base URL 或模型 ID 不正确"
    429 -> "额度不足或请求过于频繁"
    else -> "AI 服务请求失败（HTTP $status）"
}
