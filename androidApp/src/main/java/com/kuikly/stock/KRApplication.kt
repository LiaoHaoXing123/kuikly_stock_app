// App 入口类，负责应用启动时的全局初始化。

package com.kuikly.stock

import android.app.Application
import com.kuikly.stock.data.initLocalDataService
import com.kuikly.stock.data.initStockDb
import com.kuikly.stock.update.AlertNotifier
import com.kuikly.stock.update.DataUpdateWorker

class KRApplication : Application() {

    init {
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        initLocalDataService(this)
        initStockDb(this)
        AlertNotifier.createChannel(this)
        DataUpdateWorker.schedule(this)
    }

    companion object {
        lateinit var application: Application
    }
}
