package com.kuikly.stock.data

actual object DataUpdater {
    actual suspend fun refreshNow(): Boolean = false
}
