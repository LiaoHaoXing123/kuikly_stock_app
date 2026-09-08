// iOS 数据更新：无 SQLite 可导入，刷新恒为 false（调用方显示「数据已是最新」）。
// 行情来自随包内置的 JSON 快照，随 App 版本更新。

package com.kuikly.stock.data

actual object DataUpdater {
    actual suspend fun refreshNow(): Boolean = false
}
