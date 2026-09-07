// 数据下载源，Gitee 公开仓库的 raw 地址，手机端可匿名直连下载。

package com.kuikly.stock.data

const val STOCK_DATA_BASE = "https://gitee.com/LiaoHaoXing123/kuikly-stock-data/raw/master"

expect object DataUpdater {
    suspend fun refreshNow(): Boolean
}
