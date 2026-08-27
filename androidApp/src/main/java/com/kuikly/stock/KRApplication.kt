package com.kuikly.stock

import android.app.Application
import com.kuikly.stock.data.initLocalDataService
import com.kuikly.stock.data.initStockDb
import com.kuikly.stock.update.DataUpdateWorker

class KRApplication : Application() {

    init {
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化本地数据服务（读取 assets 中的 JSON，离线回退用）
        initLocalDataService(this)
        // 初始化 SQLite 数据层（方案 B：读 assets/stock.db，行情/指标/分时/盘口全部离线）
        initStockDb(this)
        // 方案A：WorkManager 后台定时/启动兜底下载 GitHub Release 的最新 stock.db
        DataUpdateWorker.schedule(this)
    }

    companion object {
        lateinit var application: Application
    }
}