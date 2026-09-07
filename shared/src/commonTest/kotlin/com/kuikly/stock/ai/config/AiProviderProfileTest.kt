package com.kuikly.stock.ai.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiProviderProfileTest {

    @Test
    fun normalizesEndpointWithoutInventingV1() {
        val profile = AiProviderProfile(
            id = "p",
            name = "Custom",
            baseUrl = "https://host.example/v1/",
            model = "model-a",
            toolsEnabled = true,
        )

        assertEquals(
            "https://host.example/v1/chat/completions",
            profile.chatCompletionsUrl(),
        )
    }

    @Test
    fun acceptsHttpsAndLocalHttpOnly() {
        assertNull(validateBaseUrl("https://host.example/v1"))
        assertNull(validateBaseUrl("http://127.0.0.1:8000/v1"))
        assertNull(validateBaseUrl("http://localhost:8000/v1"))
        assertEquals("生产服务必须使用 HTTPS", validateBaseUrl("http://host.example/v1"))
    }

    @Test
    fun masksSecretWithoutRevealingPrefix() {
        assertEquals("•••• ·cdef", maskApiKey("abcdefghijklmnopcdef"))
        assertEquals("未配置", maskApiKey(""))
    }

    @Test
    fun mapsProviderErrorsToActionableMessages() {
        assertEquals("API Key 无效或没有权限", providerErrorMessage(401))
        assertEquals("API Key 无效或没有权限", providerErrorMessage(403))
        assertEquals("Base URL 或模型 ID 不正确", providerErrorMessage(404))
        assertEquals("额度不足或请求过于频繁", providerErrorMessage(429))
        assertEquals("AI 服务请求失败（HTTP 500）", providerErrorMessage(500))
    }
}
