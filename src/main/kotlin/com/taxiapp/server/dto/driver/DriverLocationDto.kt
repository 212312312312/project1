package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.user.Driver

data class DriverLocationDto(
    val driverId: Long,
    val fullName: String,
    val lat: Double,
    val lng: Double,
    val bearing: Float,
    val status: String,
    val isOnline: Boolean,  // <--- ДОБАВИЛИ ЭТО ПОЛЕ
    val carModel: String,
    val carColor: String
) {
    // Вторичный конструктор для удобства
    constructor(driver: Driver) : this(
        driverId = driver.id!!,
        fullName = driver.fullName ?: "Водій",
        lat = driver.latitude ?: 0.0,
        lng = driver.longitude ?: 0.0,
        bearing = driver.bearing ?: 0f,
        status = driver.searchMode.name,
        isOnline = driver.isOnline, // <--- ЗАПОЛНЯЕМ ЕГО ИЗ DRIVER
        carModel = driver.car?.model ?: "Не вказано",
        carColor = driver.car?.color ?: ""
    )
}