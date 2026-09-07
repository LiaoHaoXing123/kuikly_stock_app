// 非敏感的默认请求参数。服务地址、模型和 API Key 由用户运行时档案提供。

package com.kuikly.stock.ai.config

object DeepSeekConfig {
    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"
    const val BASE_URL: String = DEFAULT_BASE_URL
    const val MODEL: String = DEFAULT_MODEL
    const val API_KEY: String = ""
    const val MAX_TOKENS = 4096
    const val TEMPERATURE = 0.7
    val enabled: Boolean get() = false
}
