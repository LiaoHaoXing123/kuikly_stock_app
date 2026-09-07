// DeepSeek 直连配置（AI 配置层）。密钥由构建期 .env 注入（AiSecretsGenerated），不入源码、不入库。

package com.kuikly.stock.ai.config

object DeepSeekConfig {
    const val BASE_URL: String = AiSecretsGenerated.BASE_URL
    const val MODEL: String = AiSecretsGenerated.MODEL
    const val API_KEY: String = AiSecretsGenerated.API_KEY
    const val MAX_TOKENS = 4096
    const val TEMPERATURE = 0.7
    val enabled: Boolean get() = API_KEY.isNotEmpty() && !API_KEY.startsWith("sk-xxxx")
}
