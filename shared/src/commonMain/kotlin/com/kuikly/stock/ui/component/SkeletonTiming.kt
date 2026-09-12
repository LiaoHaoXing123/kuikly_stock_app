package com.kuikly.stock.ui.component

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Continue on the owning Kuikly page queue, including when another page is visible. */
internal suspend fun PagerScope.ensureSkeletonVisible(startedAt: Long) {
    awaitSkeletonMinimum(startedAt, System.currentTimeMillis()) { wait, callback ->
        setTimeout(pagerId, wait, callback)
    }
}

internal suspend fun awaitSkeletonMinimum(
    startedAt: Long,
    now: Long,
    schedule: (Int, () -> Unit) -> Unit,
) {
    val remaining = (MIN_SKELETON_SHOW_MS - (now - startedAt)).coerceIn(0L, MIN_SKELETON_SHOW_MS)
    if (remaining == 0L) return
    suspendCoroutine<Unit> { continuation ->
        schedule(remaining.toInt()) { continuation.resume(Unit) }
    }
}
