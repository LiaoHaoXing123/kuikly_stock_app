// 非敏感的默认请求参数。服务地址、模型和 API Key 由用户运行时档案提供。

package com.kuikly.stock.ai.config

object DeepSeekConfig {
    // 8192：推理型模型（deepseek-flash 等）会先消耗大量 token 生成思考链，4096 会导致正文 JSON 被截断
    const val MAX_TOKENS = 8192
    const val TEMPERATURE = 0.7
}
