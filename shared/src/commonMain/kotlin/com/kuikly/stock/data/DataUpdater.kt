package com.kuikly.stock.data

const val STOCK_DATA_BASE = "https://gitee.com/LiaoHaoXing123/kuikly-stock-data/raw/master"

expect object DataUpdater {
    suspend fun refreshNow(): Boolean
}
