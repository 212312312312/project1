package com.taxiapp.server.dto.promo

data class ClientPromoProgressDto(
    val id: Long,
    val title: String,
    val description: String,
    val requiredRides: Int,
    val currentRides: Int,
    val discountPercent: Double,
    val isRewardAvailable: Boolean
)

data class ActiveDiscountDto(
    val percent: Double // Наприклад 15.0, або 0.0 якщо немає
)