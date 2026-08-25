package com.kuikly.stock

import android.app.Application
import com.kuikly.stock.data.initLocalDataService
import com.kuikly.stock.data.initStockDb

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
    }

    companion object {
        lateinit var application: Application
    }
}