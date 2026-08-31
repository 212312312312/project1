package com.taxiapp.server.dto.promo

// DTO для адмінки (список завдань)
// В PromoTaskDto добавляем:
data class PromoTaskDto(
    val id: Long,
    val title: String,
    val description: String,
    val requiredRides: Int,
    val requiredDistanceMeters: Long,
    val discountPercent: Double,
    val maxDiscountAmount: Double?,
    val isActive: Boolean,
    val requiredTariffName: String?,
    val isOneTime: Boolean,
    val activeDaysDuration: Int?,
    val maxAllocations: Int?,
    val taskDurationDays: Int?,
    val expiresAt: String?
)

// В ClientPromoProgressDto добавляем taskExpiresAt:
data class ClientPromoProgressDto(
    val id: Long,
    val title: String,
    val description: String,
    val requiredRides: Int,
    val currentRides: Int,
    val discountPercent: Double,
    val isRewardAvailable: Boolean,
    val requiredTariffName: String? = null,
    val isFullyCompleted: Boolean = false,
    val maxDiscountAmount: Double? = null,
    val requiredDistanceMeters: Long = 0,
    val currentDistanceMeters: Long = 0,
    val rewardExpiresAt: String? = null,
    val taskExpiresAt: String? = null // <- Срок действия самого задания
)

// DTO для активної знижки
data class ActiveDiscountDto(
    val percent: Double,
    val maxDiscountAmount: Double? = null
)