package com.taxiapp.server.dto.order

data class CalculatedTariffDto(
    val id: Long,
    val name: String,
    val iconUrl: String?,
    val calculatedPrice: Double,
    val description: String?
)