package com.taxiapp.server.dto.order

import java.io.Serializable

data class WaypointDto(
    val address: String,
    val lat: Double,
    val lng: Double,
    val stopOrder: Int // <-- Додали це поле
) : Serializable