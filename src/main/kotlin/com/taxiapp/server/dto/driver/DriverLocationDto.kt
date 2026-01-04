package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.user.Driver

data class DriverLocationDto(
    val id: Long,
    val fullName: String,
    val latitude: Double,
    val longitude: Double,
    val isOnline: Boolean
) {
    constructor(driver: Driver) : this(
        id = driver.id,
        fullName = driver.fullName,
        latitude = driver.currentLatitude ?: 0.0,
        longitude = driver.currentLongitude ?: 0.0,
        isOnline = driver.isOnline
    )
}