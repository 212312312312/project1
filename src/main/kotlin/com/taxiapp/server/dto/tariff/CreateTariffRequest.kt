package com.taxiapp.server.dto.tariff

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

// DTO для СТВОРЕННЯ нового тарифу
data class CreateTariffRequest(
    @field:NotBlank(message = "Назва тарифу не може бути порожньою")
    val name: String,

    @field:NotNull
    @field:Min(value = 0, message = "Базова ціна не може бути негативною")
    val basePrice: Double,

    @field:NotNull
    @field:Min(value = 0, message = "Ціна за км не може бути негативною")
    val pricePerKm: Double,

    @field:NotNull
    @field:Min(value = 0, message = "Хвилини очікування не можуть бути негативними")
    val freeWaitingMinutes: Int,

    @field:NotNull
    @field:Min(value = 0, message = "Ціна за хвилину очікування не може бути негативною")
    val pricePerWaitingMinute: Double,

    val isActive: Boolean = true
)