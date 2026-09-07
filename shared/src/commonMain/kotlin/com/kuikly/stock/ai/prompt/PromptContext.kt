// 类型化的提示词上下文：结构化承载"模型要引用的真实数据"（命中股票 + 大盘概览）， 而不是直接把一堆字符串拼接后塞进提示词。这样上下文可单独构造/测试，且渲染逻辑集中在此。 /

package com.kuikly.stock.ai.prompt

import com.kuikly.stock.pages.StockListItem

data class ChatPromptContext(
    val message: String,
    val mentioned: List<StockListItem>,
        val stockLines: List<String>,
        val marketLines: List<String>,
) {
    companion object {
        fun empty(message: String, mentioned: List<StockListItem> = emptyList()) =
            ChatPromptContext(message, mentioned, emptyList(), emptyList())
    }

    val hasData: Boolean get() = stockLines.isNotEmpty() || marketLines.isNotEmpty()

    fun render(): String {
        if (!hasData) return ""
        return buildString {
            if (stockLines.isNotEmpty()) {
                append("相关股票数据：\n")
                stockLines.forEach { append(it).append("\n") }
            }
            if (marketLines.isNotEmpty()) {
                append("\n")
                marketLines.forEach { append(it).append("\n") }
            }
        }
    }
}
