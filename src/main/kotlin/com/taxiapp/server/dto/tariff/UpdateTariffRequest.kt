package com.taxiapp.server.dto.tariff

data class UpdateTariffRequest(
    val name: String?,
    val basePrice: Double?,
    val pricePerKm: Double?,
    val freeWaitingMinutes: Int?,
    val pricePerWaitingMinute: Double?,
    val isActive: Boolean?
)