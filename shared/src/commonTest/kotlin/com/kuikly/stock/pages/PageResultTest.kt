package com.kuikly.stock.pages

import kotlin.coroutines.*
import kotlin.test.*

class PageResultTest {
    @Test fun successWaitsForPageDispatch() {
        var dispatch: (() -> Unit)? = null
        var result: Result<Int>? = null
        val work: suspend () -> Int = { awaitPageResult({ dispatch = it }) { 42 } }
        work.startCoroutine(object : Continuation<Int> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<Int>) { result = value }
        })
        assertNull(result)
        dispatch!!()
        assertEquals(42, result!!.getOrThrow())
    }
    @Test fun failureAlsoReturnsThroughPageDispatch() {
        var dispatch: (() -> Unit)? = null
        var result: Result<Int>? = null
        val error = IllegalStateException("offline")
        val work: suspend () -> Int = { awaitPageResult({ dispatch = it }) { throw error } }
        work.startCoroutine(object : Continuation<Int> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<Int>) { result = value }
        })
        assertNull(result)
        dispatch!!()
        assertSame(error, result!!.exceptionOrNull())
    }
    @Test fun suspendedRequestDoesNotPublishUntilPageDispatch() {
        var request: Continuation<Int>? = null
        var dispatch: (() -> Unit)? = null
        var published: Int? = null
        val work: suspend () -> Int = {
            awaitPageResult({ dispatch = it }) { suspendCoroutine { request = it } }
        }
        work.startCoroutine(object : Continuation<Int> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Int>) { published = result.getOrThrow() }
        })
        assertNull(dispatch)
        request!!.resume(7)
        assertNull(published)
        dispatch!!()
        assertEquals(7, published)
    }
}
