// 跨平台时间入口：System.currentTimeMillis() 在 commonMain 不存在，
// 各处取当前毫秒统一走这里（kotlin.time.Clock，全平台可用）。

package com.kuikly.stock.data

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
