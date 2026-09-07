// 分享桥接模块，向 JS 层暴露系统分享能力。

package com.kuikly.stock.module

import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule

class KRShareModule : KuiklyRenderBaseModule() {
    companion object {
        const val MODULE_NAME = "HRShareModule"

    }
}
