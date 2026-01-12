package com.taxiapp.server.dto.filter

data class CreateFilterRequest(
    val name: String,
    val fromType: String,
    val fromDistance: Double?,
    val fromSectors: List<Long>,
    val toSectors: List<Long>,
    val tariffType: String,
    val minPrice: Double?,
    val minPricePerKm: Double?,
    val complexMinPrice: Double?,
    val complexKmInMin: Double?,      // ДОДАТИ ЦЕ
    val complexPriceKmCity: Double?,
    val complexPriceKmSuburbs: Double?, // ДОДАТИ ЦЕ
    val paymentType: String
)

data class DriverFilterDto(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val description: String,
    val fromType: String,
    val fromDistance: Double?,
    val fromSectors: List<Long>,
    val toSectors: List<Long>,
    val tariffType: String,
    val minPrice: Double?,
    val minPricePerKm: Double?,
    val complexMinPrice: Double?,
    val complexKmInMin: Double?,      // ДОДАТИ ЦЕ
    val complexPriceKmCity: Double?,
    val complexPriceKmSuburbs: Double?, // ДОДАТИ ЦЕ
    val paymentType: String
)