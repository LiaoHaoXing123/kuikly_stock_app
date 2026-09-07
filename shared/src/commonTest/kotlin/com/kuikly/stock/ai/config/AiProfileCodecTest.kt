package com.kuikly.stock.ai.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AiProfileCodecTest {

    @Test
    fun presetsContainNoSecret() {
        val presets = defaultAiProfiles(now = 100L)

        assertEquals(listOf("AgentRouter", "DeepSeek 官方"), presets.map { it.name })
        assertEquals("https://co.agentrouter.org/v1", presets.first().baseUrl)
        assertEquals("deepseek-v4-flash", presets.first().model)
        assertFalse(encodeAiProfiles(presets).contains("api_key", ignoreCase = true))
    }

    @Test
    fun roundTripPreservesProfileMetadata() {
        val input = listOf(
            AiProviderProfile(
                id = "p",
                name = "P",
                baseUrl = "https://p.example/v1",
                model = "m",
                toolsEnabled = false,
                createdAt = 1L,
                updatedAt = 2L,
            )
        )

        assertEquals(input, decodeAiProfiles(encodeAiProfiles(input)))
    }

    @Test
    fun malformedMetadataFallsBackToEmptyList() {
        assertEquals(emptyList(), decodeAiProfiles("not-json"))
        assertEquals(emptyList(), decodeAiProfiles(null))
    }

    @Test
    fun removingActiveProfileSelectsFirstRemainingProfile() {
        val result = selectAfterRemoval(
            profileIds = listOf("a", "b", "c"),
            activeId = "b",
            removedId = "b",
        )

        assertEquals("a", result)
        assertEquals("b", selectAfterRemoval(listOf("a", "b"), "b", "a"))
        assertEquals(null, selectAfterRemoval(listOf("a"), "a", "a"))
    }
}
