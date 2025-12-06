package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.user.Driver // <-- ИМПОРТ

data class DriverLocationDto(
    val id: Long,
    val fullName: String,
    val latitude: Double,
    val longitude: Double
) {
    constructor(driver: Driver) : this(
        id = driver.id,
        fullName = driver.fullName,
        latitude = driver.currentLatitude ?: 0.0,
        longitude = driver.currentLongitude ?: 0.0
    )
}