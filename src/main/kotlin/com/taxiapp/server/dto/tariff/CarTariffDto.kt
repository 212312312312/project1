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
    var calculatedPrice: Double? = null,
    val description: String? = null,
    var oldPrice: Double? = null
) {
    // Вторинний конструктор із параметром fullImageUrl за замовчуванням (null)
    constructor(tariff: CarTariff, fullImageUrl: String? = null) : this(
        id = tariff.id,
        name = tariff.name,
        basePrice = tariff.basePrice,
        pricePerKm = tariff.pricePerKm,
        sortOrder = tariff.sortOrder,
        pricePerKmOutCity = tariff.pricePerKmOutCity,
        freeWaitingMinutes = tariff.freeWaitingMinutes,
        extraWaypointPrice = tariff.extraWaypointPrice,
        isBeta = tariff.isBeta,
        isUnavailable = tariff.isUnavailable,
        pricePerWaitingMinute = tariff.pricePerWaitingMinute,
        isActive = tariff.isActive,
        imageUrl = fullImageUrl ?: tariff.imageUrl,
        
        calculatedPrice = null,
        description = null,
        oldPrice = null
    )
}