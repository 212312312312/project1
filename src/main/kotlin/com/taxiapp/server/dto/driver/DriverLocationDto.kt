package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.user.Driver

data class DriverLocationDto(
    val driverId: Long,     // Используем driverId для ясности
    val fullName: String,
    val lat: Double,
    val lng: Double,
    val bearing: Float,     // Добавили поворот
    val status: String,     // Статус (MANUAL, BUSY, etc.)
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
        carModel = driver.car?.model ?: "Не вказано",
        carColor = driver.car?.color ?: ""
    )
}