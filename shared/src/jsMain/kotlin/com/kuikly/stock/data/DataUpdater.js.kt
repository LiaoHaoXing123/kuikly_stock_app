// JS 数据更新：无本地数据库可导入，刷新恒为 false。

package com.kuikly.stock.data

actual object DataUpdater {
    actual suspend fun refreshNow(): Boolean = false
}
