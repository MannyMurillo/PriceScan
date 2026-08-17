package com.pricescan.app.model

data class StorePrice(
    val store: String,
    val price: Double,
    val unit: String = "each",
    val emoji: String = "🏪",
    val note: String = "",
    val isAiEstimate: Boolean = false
) {
    val formattedPrice: String
        get() = "$%.2f".format(price)
}

data class ProductInfo(
    val barcode: String,
    val name: String,
    val brand: String = "",
    val imageUrl: String = "",
    val prices: List<StorePrice> = emptyList(),
    val isAiPowered: Boolean = false,
    val aiInsight: String = ""
) {
    val cheapestPrice: StorePrice?
        get() = prices.minByOrNull { it.price }

    val priciestPrice: StorePrice?
        get() = prices.maxByOrNull { it.price }

    val maxSavings: Double
        get() {
            val cheap = cheapestPrice?.price ?: 0.0
            val pricey = priciestPrice?.price ?: 0.0
            return (pricey - cheap).coerceAtLeast(0.0)
        }
}
