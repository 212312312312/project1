package com.taxiapp.server.dto.tariff

import com.taxiapp.server.model.order.CarTariff

data class CarTariffDto(
    val id: Long,
    val name: String,
    val basePrice: Double,
    val pricePerKm: Double,
    val pricePerKmOutCity: Double, // <-- НОВЕ ПОЛЕ
    val freeWaitingMinutes: Int,
    val pricePerWaitingMinute: Double,
    val isActive: Boolean,
    val imageUrl: String? 
) {
    constructor(tariff: CarTariff) : this(
        id = tariff.id,
        name = tariff.name,
        basePrice = tariff.basePrice,
        pricePerKm = tariff.pricePerKm,
        pricePerKmOutCity = tariff.pricePerKmOutCity, // <-- Додано
        freeWaitingMinutes = tariff.freeWaitingMinutes,
        pricePerWaitingMinute = tariff.pricePerWaitingMinute,
        isActive = tariff.isActive,
        imageUrl = tariff.imageUrl 
    )
}