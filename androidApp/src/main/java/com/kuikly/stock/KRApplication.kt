package com.kuikly.stock

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {

                StockKlineWebView.ChartBridgeHolder.evict()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    companion object {
        lateinit var application: Application
    }
}
