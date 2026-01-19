package com.taxiapp.server.dto.order

data class CalculatePriceRequest(
    val googleRoutePolyline: String,
    val distanceMeters: Int
)