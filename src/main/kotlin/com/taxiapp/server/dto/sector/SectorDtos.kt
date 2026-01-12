package com.taxiapp.server.dto.sector

data class PointDto(
    val lat: Double,
    val lng: Double
)

data class SectorDto(
    val id: Long,
    val name: String,
    val points: List<PointDto>
)

data class CreateSectorRequest(
    val name: String,
    val points: List<PointDto>
)