package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatScrollCoordinatorTest {

    @Test
    fun consumesExactlyOnceForEachSendRequest() {
        val coordinator = ChatScrollCoordinator()

        assertNull(coordinator.consumeBottomOffset(1200f, 600f))

        coordinator.requestScrollAfterSend()
        assertEquals(600f, coordinator.consumeBottomOffset(1200f, 600f))
        assertNull(coordinator.consumeBottomOffset(1400f, 600f))

        coordinator.requestScrollAfterSend()
        assertEquals(800f, coordinator.consumeBottomOffset(1400f, 600f))
        assertNull(coordinator.consumeBottomOffset(1600f, 600f))
    }

    @Test
    fun clampsOffsetWhenContentIsShorterThanViewport() {
        val coordinator = ChatScrollCoordinator()

        coordinator.requestScrollAfterSend()

        assertEquals(0f, coordinator.consumeBottomOffset(300f, 600f))
    }

    @Test
    fun keepsRequestPendingUntilLayoutIsUsable() {
        val coordinator = ChatScrollCoordinator()

        coordinator.requestScrollAfterSend()

        assertNull(coordinator.consumeBottomOffset(0f, 0f))
        assertEquals(500f, coordinator.consumeBottomOffset(1000f, 500f))
        assertNull(coordinator.consumeBottomOffset(1100f, 500f))
    }
}
