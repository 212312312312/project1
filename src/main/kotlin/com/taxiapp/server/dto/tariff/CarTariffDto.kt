package com.taxiapp.server.dto.tariff

import com.taxiapp.server.model.order.CarTariff

data class CarTariffDto(
    val id: Long,
    val name: String,
    val basePrice: Double,
    val pricePerKm: Double,
    val sortOrder: Int = 0,
    val pricePerKmOutCity: Double,
    val freeWaitingMinutes: Int,
    val pricePerWaitingMinute: Double,
    val extraWaypointPrice: Double,
    @get:com.fasterxml.jackson.annotation.JsonProperty("isActive")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isActive")
    val isActive: Boolean,
    val imageUrl: String?,
    val isBeta: Boolean,
    val isUnavailable: Boolean,
    
    // --- НОВІ ПОЛЯ ДЛЯ SMART PRICING ---
    // Вони можуть бути null, якщо ми просто переглядаємо список тарифів в адмінці
    var calculatedPrice: Double? = null,
    val description: String? = null,
    var oldPrice: Double? = null
) {
    // Конструктор для конвертації з Entity (БД)
    constructor(tariff: CarTariff) : this(
        id = tariff.id,
        name = tariff.name,
        basePrice = tariff.basePrice,
        pricePerKm = tariff.pricePerKm,
        pricePerKmOutCity = tariff.pricePerKmOutCity,
        freeWaitingMinutes = tariff.freeWaitingMinutes,
        extraWaypointPrice = tariff.extraWaypointPrice,
        isBeta = tariff.isBeta,               // Маппинг
        isUnavailable = tariff.isUnavailable,
        pricePerWaitingMinute = tariff.pricePerWaitingMinute,
        isActive = tariff.isActive,
        imageUrl = tariff.imageUrl,
        
        // За замовчуванням ціна не порахована
        calculatedPrice = null,
        description = null,
        oldPrice = null
    )
}