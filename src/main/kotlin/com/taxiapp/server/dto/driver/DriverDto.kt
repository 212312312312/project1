package com.taxiapp.server.dto.driver

import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.model.user.Driver
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.LocalDateTime

data class DriverDto(
    val id: Long,
    val phoneNumber: String,
    val fullName: String,
    val email: String?,
    val rnokpp: String?,
    val driverLicense: String?,
    val isOnline: Boolean,
    val isBlocked: Boolean,
    val tempBlockExpiresAt: LocalDateTime?,
    val rating: Double, // Добавил рейтинг в DTO
    val ratingCount: Int, // Добавил кол-во оценок
    val latitude: Double?, 
    val longitude: Double?,
    
    val car: CarDto?, // Активное авто
    val cars: List<CarDto>?, // <-- НОВОЕ: Весь список машин
    
    val allowedTariffs: List<CarTariffDto>,
    val photoUrl: String?,
    val activityScore: Int
) {
    constructor(driver: Driver) : this(
        id = driver.id!!,
        phoneNumber = driver.userPhone ?: "",
        fullName = driver.fullName ?: "",
        email = driver.email,
        rnokpp = driver.rnokpp,
        driverLicense = driver.driverLicense,
        isOnline = driver.isOnline,
        isBlocked = driver.isBlocked,
        tempBlockExpiresAt = driver.tempBlockExpiresAt,
        rating = driver.rating,
        ratingCount = driver.ratingCount,
        latitude = driver.latitude,
        longitude = driver.longitude,
        
        // Маппинг активного авто
        car = driver.car?.let { CarDto(it) },
        
        // Маппинг списка всех машин (гараж)
        cars = driver.cars.map { CarDto(it) },

        allowedTariffs = driver.allowedTariffs.map { CarTariffDto(it) },
        photoUrl = driver.photoUrl?.let { filename ->
            ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/images/")
                .path(filename)
                .toUriString()
        },
        activityScore = driver.activityScore
    )
}