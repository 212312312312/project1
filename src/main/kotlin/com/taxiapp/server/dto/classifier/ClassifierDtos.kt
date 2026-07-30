package com.taxiapp.server.dto.classifier

data class CityDto(
    val id: Long,
    val name: String,
    val grade: String
)

data class CarBrandDto(
    val id: Long,
    val name: String
)

data class CarModelDto(
    val id: Long,
    val name: String
)

data class EvaluateCarRequest(
    val cityName: String,
    val modelId: Long,
    val year: Int
)

data class EvaluateCarResponse(
    val cityGrade: String,
    val maxTariffStatus: String,
    val allowedTariffs: List<String>,
    val isAllowed: Boolean
)