package com.kuikly.stock.data

/** 行情数据更新源：Gitee 公开仓库 raw（国内直连，手机可匿名下载；仓库私有故不用 GitHub Release） */
const val STOCK_DATA_BASE = "https://gitee.com/LiaoHaoXing123/kuikly-stock-data/raw/master"

/**
 * 行情数据更新器（手动刷新用）。
 * expect 由各平台 actual 实现：拉取 version.json -> 比对 -> 下载最新 stock.db -> 替换本地 SQLite。
 *
 * 约定：返回 true=已更新，false=无新数据；网络/下载失败会抛异常（调用方据此区分"无更新"与"失败"）。
 */
expect object DataUpdater {
    suspend fun refreshNow(): Boolean
}
