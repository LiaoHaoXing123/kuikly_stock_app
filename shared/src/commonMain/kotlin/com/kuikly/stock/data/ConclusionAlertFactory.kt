package com.kuikly.stock.data

internal object ConclusionAlertFactory {
    fun support(code: String, name: String, price: Double): PriceAlertRule =
        create(code, name, type = 1, price = price)

    fun resistance(code: String, name: String, price: Double): PriceAlertRule =
        create(code, name, type = 0, price = price)

    private fun create(code: String, name: String, type: Int, price: Double): PriceAlertRule {
        require(code.isNotBlank()) { "股票代码不能为空" }
        require(price.isFinite() && price > 0.0) { "提醒价格必须为有效正数" }
        return PriceAlertRule(
            code = code.trim(),
            name = name.trim().ifBlank { code.trim() },
            type = type,
            threshold = price,
            enabled = true,
        )
    }
}
