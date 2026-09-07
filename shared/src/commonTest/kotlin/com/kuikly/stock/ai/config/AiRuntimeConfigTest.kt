package com.kuikly.stock.ai.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiRuntimeConfigTest {

    @Test
    fun configuredProfileResolvesRequestConfig() {
        val profile = AiProviderProfile(
            id = "a",
            name = "AgentRouter",
            baseUrl = "https://co.agentrouter.org/v1",
            model = "model-a",
            toolsEnabled = true,
        )

        assertEquals(
            AiRequestConfig(
                profileId = "a",
                providerName = "AgentRouter",
                endpoint = "https://co.agentrouter.org/v1/chat/completions",
                model = "model-a",
                apiKey = "secret",
                toolsEnabled = true,
            ),
            resolveAiRequestConfig(profile, "secret"),
        )
    }

    @Test
    fun blankSecretRequiresConfiguration() {
        val profile = AiProviderProfile(
            id = "a",
            name = "A",
            baseUrl = "https://a.example/v1",
            model = "model-a",
            toolsEnabled = true,
        )

        assertFailsWith<AiConfigurationException> {
            resolveAiRequestConfig(profile, "")
        }
    }

    @Test
    fun invalidProfileIsRejectedBeforeNetworkRequest() {
        val profile = AiProviderProfile(
            id = "a",
            name = "A",
            baseUrl = "http://insecure.example/v1",
            model = "model-a",
            toolsEnabled = false,
        )

        assertEquals(
            "生产服务必须使用 HTTPS",
            assertFailsWith<AiConfigurationException> {
                resolveAiRequestConfig(profile, "secret")
            }.message,
        )
    }
}
