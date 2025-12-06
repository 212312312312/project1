package com.taxiapp.server.dto.driver

import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.model.user.Driver
import java.time.LocalDateTime

data class DriverDto(
    val id: Long,
    val phoneNumber: String, 
    val fullName: String,
    val isOnline: Boolean,
    val isBlocked: Boolean, 
    val tempBlockExpiresAt: LocalDateTime?,
    val currentLatitude: Double?,
    val currentLongitude: Double?,
    val car: CarDto?, // Використовуємо вкладений DTO
    val allowedTariffs: List<CarTariffDto>
) {
    constructor(driver: Driver) : this(
        id = driver.id,
        // ВИПРАВЛЕННЯ: Використовуємо безпечний виклик ?: "" замість !!
        phoneNumber = driver.userPhone ?: "", 
        fullName = driver.fullName,
        isOnline = driver.isOnline,
        isBlocked = driver.isBlocked,
        tempBlockExpiresAt = driver.tempBlockExpiresAt,
        currentLatitude = driver.currentLatitude,
        currentLongitude = driver.currentLongitude,
        // Перевіряємо, чи є машина
        car = driver.car?.let { CarDto(it) }, 
        // Конвертуємо тарифи
        allowedTariffs = driver.allowedTariffs.map { CarTariffDto(it) }
    )
}