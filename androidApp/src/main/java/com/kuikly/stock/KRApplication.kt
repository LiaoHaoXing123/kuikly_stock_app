package com.kuikly.stock

import android.app.Application
import com.kuikly.stock.data.initLocalDataService

class KRApplication : Application() {

    init {
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化本地数据服务（读取 assets 中的 JSON）
        initLocalDataService(this)
    }

    companion object {
        lateinit var application: Application
    }
}