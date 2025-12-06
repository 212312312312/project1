package com.taxiapp.server.dto.tariff

import com.taxiapp.server.model.order.CarTariff

data class CarTariffDto(
    val id: Long,
    val name: String,
    val basePrice: Double,
    val pricePerKm: Double,
    val freeWaitingMinutes: Int,
    val pricePerWaitingMinute: Double,
    val isActive: Boolean,
    val imageUrl: String? // <-- НОВОЕ ПОЛЕ (будет полный URL)
) {
    constructor(tariff: CarTariff) : this(
        id = tariff.id,
        name = tariff.name,
        basePrice = tariff.basePrice,
        pricePerKm = tariff.pricePerKm,
        freeWaitingMinutes = tariff.freeWaitingMinutes,
        pricePerWaitingMinute = tariff.pricePerWaitingMinute,
        isActive = tariff.isActive,
        // Мы НЕ будем добавлять http://localhost... здесь.
        // Это сделает TariffAdminService.
        imageUrl = tariff.imageUrl 
    )
}