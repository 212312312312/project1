package com.taxiapp.server.dto.driver

import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.model.user.Driver
import org.springframework.web.servlet.support.ServletUriComponentsBuilder // <-- ВАЖНЫЙ ИМПОРТ
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
    val car: CarDto?,
    val allowedTariffs: List<CarTariffDto>,
    val photoUrl: String? // <-- НОВОЕ ПОЛЕ
) {
    constructor(driver: Driver) : this(
        id = driver.id,
        phoneNumber = driver.userPhone ?: "", 
        fullName = driver.fullName,
        isOnline = driver.isOnline,
        isBlocked = driver.isBlocked,
        tempBlockExpiresAt = driver.tempBlockExpiresAt,
        currentLatitude = driver.currentLatitude,
        currentLongitude = driver.currentLongitude,
        car = driver.car?.let { CarDto(it) }, 
        allowedTariffs = driver.allowedTariffs.map { CarTariffDto(it) },
        
        // ГЕНЕРАЦИЯ ССЫЛКИ НА ФОТО
        photoUrl = driver.photoUrl?.let { filename ->
            ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/images/")
                .path(filename)
                .toUriString()
        }
    )
}