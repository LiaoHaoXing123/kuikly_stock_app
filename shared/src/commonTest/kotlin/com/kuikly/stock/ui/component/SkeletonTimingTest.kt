package com.kuikly.stock.ui.component

import kotlin.coroutines.*
import kotlin.test.*

class SkeletonTimingTest {
    @Test fun fastLoadResumesOnlyThroughScheduledPageCallback() {
        var scheduledDelay = -1
        var callback: (() -> Unit)? = null
        var finished = false
        val work: suspend () -> Unit = {
            awaitSkeletonMinimum(1000L, 1120L) { delay, action ->
                scheduledDelay = delay
                callback = action
            }
            finished = true
        }
        work.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
        })
        assertEquals(280, scheduledDelay)
        assertFalse(finished)
        callback!!()
        assertTrue(finished)
    }

    @Test fun slowLoadDoesNotScheduleAnotherWait() {
        var finished = false
        val work: suspend () -> Unit = {
            awaitSkeletonMinimum(1000L, 1500L) { _, _ -> fail("No additional wait") }
            finished = true
        }
        work.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
        })
        assertTrue(finished)
    }
}
