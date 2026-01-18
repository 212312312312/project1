package com.taxiapp.server.dto.driver

data class SosSignalDto(
    val driverId: Long,
    val driverName: String,
    val phone: String,
    val carNumber: String,
    val lat: Double,
    val lng: Double,
    val timestamp: String
)