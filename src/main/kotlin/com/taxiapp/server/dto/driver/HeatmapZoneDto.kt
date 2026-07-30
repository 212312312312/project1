package com.taxiapp.server.dto.driver

data class HeatmapZoneDto(
    val centerLat: Double,
    val centerLng: Double,
    val orderCount: Int,
    val level: Int // 1, 2 або 3 (залежно від кількості)
)