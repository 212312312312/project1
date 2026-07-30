package com.taxiapp.server.dto.promo

import java.time.LocalDateTime

data class CreatePromoCodeRequest(
    val code: String,
    val discountPercent: Double,
    val maxDiscountAmount: Double?,
    val usageLimit: Int?,
    val activeDays: Int?, // Срок активации (глобальный)
    val durationHours: Int? // НОВОЕ: Срок жизни после активации (в часах)
)

data class PromoCodeDto(
    val id: Long,
    val code: String,
    val discountPercent: Double,
    val maxDiscountAmount: Double?,
    val usageLimit: Int?,
    val usedCount: Int,
    val expiresAt: LocalDateTime?,
    val activationDurationHours: Int? // НОВОЕ
)