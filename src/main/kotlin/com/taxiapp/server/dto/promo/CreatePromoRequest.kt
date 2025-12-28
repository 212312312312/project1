package com.taxiapp.server.dto.promo

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class CreatePromoRequest(
    @field:NotBlank
    val title: String,

    @field:NotBlank
    val description: String,

    @field:Min(0) // Тепер може бути 0
    val requiredRides: Int = 0,

    @field:NotNull
    @field:Min(1)
    val discountPercent: Double,

    val requiredTariffId: Long?,
    val isOneTime: Boolean = true,
    val maxDiscountAmount: Double?,

    // --- НОВЕ ПОЛЕ (в адмінці вводимо км) ---
    @field:Min(0)
    val requiredDistanceKm: Double = 0.0,

    val activeDaysDuration: Int? = null
)