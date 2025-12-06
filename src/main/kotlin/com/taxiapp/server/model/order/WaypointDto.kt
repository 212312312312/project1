package com.taxiapp.server.dto.order

data class WaypointDto(
    val address: String,
    val lat: Double,
    val lng: Double
)