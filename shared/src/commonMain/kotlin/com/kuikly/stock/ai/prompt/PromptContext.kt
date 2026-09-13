// 将本地行情和数据日期加入模型上下文。

package com.kuikly.stock.ai.prompt

import com.kuikly.stock.pages.StockListItem

data class ChatPromptContext(
    val message: String,
    val mentioned: List<StockListItem>,
        val stockLines: List<String>,
        val marketLines: List<String>,
        val indexLines: List<String> = emptyList(),
) {
    companion object {
        fun empty(message: String, mentioned: List<StockListItem> = emptyList()) =
            ChatPromptContext(message, mentioned, emptyList(), emptyList())
    }

    val hasData: Boolean get() = stockLines.isNotEmpty() || marketLines.isNotEmpty() || indexLines.isNotEmpty()

    fun render(): String {
        if (!hasData) return ""
        return buildString {
            if (stockLines.isNotEmpty()) {
                append("相关股票数据：\n")
                stockLines.forEach { append(it).append("\n") }
            }
            if (indexLines.isNotEmpty()) {
                append("相关指数数据：\n")
                indexLines.forEach { append(it).append("\n") }
            }
            if (marketLines.isNotEmpty()) {
                append("\n")
                marketLines.forEach { append(it).append("\n") }
            }
        }
    }
}
