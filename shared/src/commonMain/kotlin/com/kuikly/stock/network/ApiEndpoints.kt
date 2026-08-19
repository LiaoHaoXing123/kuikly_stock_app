package com.kuikly.stock.network

/**
 * API 端点定义
 */
object ApiEndpoints {

    // 基础 URL（开发环境）
    const val BASE_URL = "http://127.0.0.1:8000"

    // ==================== 股票接口 ====================

    object Stocks {
        private const val BASE = "/api/v1/stocks"

        // 获取股票列表
        const val LIST = "$BASE"

        // 获取个股基础信息
        const val INFO = "$BASE/{code}"

        // 获取个股完整详情
        const val DETAIL = "$BASE/{code}/detail"
    }

    // ==================== 行情接口 ====================

    object Market {
        private const val BASE = "/api/v1/market"

        // 全市场实时行情
        const val REALTIME = "$BASE/realtime"

        // 单股实时行情
        const val REALTIME_CODE = "$BASE/realtime/{code}"

        // 涨幅榜
        const val TOP_GAINERS = "$BASE/top/gainers"

        // 跌幅榜
        const val TOP_LOSERS = "$BASE/top/losers"

        // 最活跃
        const val MOST_ACTIVE = "$BASE/top/active"
    }

    // ==================== K线接口 ====================

    object KLine {
        private const val BASE = "/api/v1/kline"

        // K线数据
        const val DATA = "$BASE/{code}"

        // 最近N天K线
        const val LATEST = "$BASE/{code}/latest"
    }

    // ==================== AI 接口 ================= ====================

    object AI {
        private const val BASE = "/api/v1/ai"

        // AI 个股分析
        const val ANALYZE = "$BASE/analyze/{code}"

        // AI 问答
        const val CHAT = "$BASE/chat"

        // 会话列表
        const val SESSIONS = "$BASE/sessions"

        // 会话消息记录
        const val SESSION_MESSAGES = "$BASE/sessions/{id}/messages"
    }
}
