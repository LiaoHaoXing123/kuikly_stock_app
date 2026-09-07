// 线程适配器，为 Kuikly 框架提供主线程与子线程的调度能力。

package com.kuikly.stock.adapter

import com.tencent.kuikly.core.render.android.adapter.IKRThreadAdapter
import java.util.concurrent.Executors

class KRThreadAdapter : IKRThreadAdapter {
    override fun executeOnSubThread(task: () -> Unit) {
        execOnSubThread(task)
    }
}

private val subThreadPoolExecutor by lazy {
    Executors.newFixedThreadPool(2)
}

fun execOnSubThread(runnable: () -> Unit) {
    subThreadPoolExecutor.execute(runnable)
}
