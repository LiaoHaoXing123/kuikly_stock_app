// 仅消费发送后的一次滚动请求，避免流式更新打断阅读。

package com.kuikly.stock.pages

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
