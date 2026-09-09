package com.kuikly.stock.data

internal data class HoldingInput(val code: String, val shares: Double, val cost: Double) {
    companion object {
        private val decimal = Regex("[0-9]+(?:\\.[0-9]+)?")

        fun parse(code: String, shares: String, cost: String, allowClear: Boolean = false): HoldingInput? {
            val normalizedCode = code.trim()
            if (normalizedCode.length != 6 || normalizedCode.any { it !in '0'..'9' }) return null
            val quantityText = shares.trim()
            val priceText = cost.trim()
            if (quantityText.length > 320 || priceText.length > 320 ||
                !decimal.matches(quantityText) || !decimal.matches(priceText)) return null
            val quantity = quantityText.toDoubleOrNull() ?: return null
            val price = priceText.toDoubleOrNull() ?: return null
            if (!quantity.isFinite() || !price.isFinite() || quantity < 0.0 || price < 0.0) return null
            val explicitZero = quantityText.all { it == '0' || it == '.' }
            if (allowClear && quantity == 0.0 && explicitZero) return HoldingInput(normalizedCode, 0.0, 0.0)
            if (quantity <= 0.0 || price <= 0.0) return null
            if (!(quantity * price).isFinite()) return null
            return HoldingInput(normalizedCode, quantity, price)
        }
    }
}
