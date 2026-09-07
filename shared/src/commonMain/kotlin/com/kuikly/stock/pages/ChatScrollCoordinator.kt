package com.kuikly.stock.pages

/**
 * Coordinates the chat's one-shot scroll behavior without owning any UI state.
 * Each send request can be consumed by exactly one usable layout update.
 */
internal class ChatScrollCoordinator {
    private var latestRequest = 0
    private var consumedRequest = 0

    fun requestScrollAfterSend() {
        latestRequest++
    }

    fun consumeBottomOffset(contentHeight: Float, viewportHeight: Float): Float? {
        if (latestRequest == consumedRequest) return null
        if (contentHeight <= 0f || viewportHeight <= 0f) return null

        consumedRequest = latestRequest
        return (contentHeight - viewportHeight).coerceAtLeast(0f)
    }
}
