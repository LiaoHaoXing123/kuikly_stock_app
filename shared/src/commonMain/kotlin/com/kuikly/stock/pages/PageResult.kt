package com.kuikly.stock.pages

import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.suspendCoroutine

internal suspend fun <T> awaitPageResult(dispatch: (() -> Unit) -> Unit, block: suspend () -> T): T {
    val result = runCatching { block() }
    return suspendCoroutine { continuation ->
        dispatch { continuation.resumeWith(result) }
    }
}

internal suspend fun <T> Pager.pageResult(block: suspend () -> T): T =
    awaitPageResult({ callback -> setTimeout(pagerId, 0) { callback() } }, block)
