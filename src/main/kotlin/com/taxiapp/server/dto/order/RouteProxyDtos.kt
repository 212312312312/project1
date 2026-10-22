package com.taxiapp.server.dto.order

import jakarta.validation.constraints.NotNull

data class RoutePointDto(
    val lat: Double,
    val lng: Double
)

data class RouteRequestDto(
    @field:NotNull
    val originLat: Double,
    @field:NotNull
    val originLng: Double,
    @field:NotNull
    val destLat: Double,
    @field:NotNull
    val destLng: Double,
    val waypoints: List<RoutePointDto>? = null
)

data class RouteResponseDto(
    val polyline: String,
    val distanceMeters: Int,
    val durationSeconds: Int
)

data class ReverseGeocodeResponseDto(
    val address: String,
    val rawDisplayName: String? = null,
    val source: String = "nominatim"
)