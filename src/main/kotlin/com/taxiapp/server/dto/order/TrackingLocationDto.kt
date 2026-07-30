package com.taxiapp.server.dto.order

data class TrackingLocationDto(
    val lat: Double,
    val lng: Double,
    val bearing: Float = 0f // Поворот машины (азимут)
)