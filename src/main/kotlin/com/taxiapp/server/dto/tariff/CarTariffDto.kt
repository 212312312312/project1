package com.taxiapp.server.dto.tariff

import com.taxiapp.server.model.order.CarTariff

data class CarTariffDto(
    val id: Long,
    val name: String,
    val basePrice: Double,
    val pricePerKm: Double,
    val pricePerKmOutCity: Double,
    val freeWaitingMinutes: Int,
    val pricePerWaitingMinute: Double,
    val isActive: Boolean,
    val imageUrl: String?,
    
    // --- НОВІ ПОЛЯ ДЛЯ SMART PRICING ---
    // Вони можуть бути null, якщо ми просто переглядаємо список тарифів в адмінці
    var calculatedPrice: Double? = null,
    val description: String? = null
) {
    // Конструктор для конвертації з Entity (БД)
    constructor(tariff: CarTariff) : this(
        id = tariff.id,
        name = tariff.name,
        basePrice = tariff.basePrice,
        pricePerKm = tariff.pricePerKm,
        pricePerKmOutCity = tariff.pricePerKmOutCity,
        freeWaitingMinutes = tariff.freeWaitingMinutes,
        pricePerWaitingMinute = tariff.pricePerWaitingMinute,
        isActive = tariff.isActive,
        imageUrl = tariff.imageUrl,
        
        // За замовчуванням ціна не порахована
        calculatedPrice = null,
        description = null
    )
}