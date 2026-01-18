package com.taxiapp.server.dto.sector

data class PointDto(
    val lat: Double,
    val lng: Double
)

data class SectorDto(
    val id: Long,
    val name: String,
    // --- ДОДАНО ---
    val isCity: Boolean, 
    // --------------
    val points: List<PointDto>
)

data class CreateSectorRequest(
    val name: String,
    // --- ДОДАНО (з дефолтним значенням true) ---
    val isCity: Boolean = true, 
    // -------------------------------------------
    val points: List<PointDto>
)