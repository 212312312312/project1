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
    
    // --- НОВЫЕ ПОЛЯ (ФОТО ПРАВ) ---
    val driverLicenseFront: String?,
    val driverLicenseBack: String?,
    // -----------------------------

    val isOnline: Boolean,
    val isBlocked: Boolean,
    val tempBlockExpiresAt: LocalDateTime?,
    val rating: Double,
    val ratingCount: Int,
    val latitude: Double?, 
    val longitude: Double?,
    
    val car: CarDto?,        // Активное авто
    val cars: List<CarDto>?, // Весь гараж
    
    val allowedTariffs: List<CarTariffDto>,
    val photoUrl: String?,   // Аватарка
    val activityScore: Int,
    val registrationStatus: String // Добавим статус, чтобы видеть PENDING/APPROVED
) {
    constructor(driver: Driver) : this(
        id = driver.id!!,
        phoneNumber = driver.userPhone ?: "",
        fullName = driver.fullName ?: "",
        email = driver.email,
        rnokpp = driver.rnokpp,
        driverLicense = driver.driverLicense,
        
        // Генерируем ссылки на фото прав
        driverLicenseFront = generateUrl(driver.driverLicenseFront),
        driverLicenseBack = generateUrl(driver.driverLicenseBack),

        isOnline = driver.isOnline,
        isBlocked = driver.isBlocked,
        tempBlockExpiresAt = driver.tempBlockExpiresAt,
        rating = driver.rating,
        ratingCount = driver.ratingCount,
        latitude = driver.latitude,
        longitude = driver.longitude,
        
        // Маппинг машины (CarDto уже настроен правильно, он подтянет фото авто, ТП и страховки)
        car = driver.car?.let { CarDto(it) },
        cars = driver.cars.map { CarDto(it) },

        allowedTariffs = driver.allowedTariffs.map { CarTariffDto(it) },
        
        // Генерируем ссылку на аватарку
        photoUrl = generateUrl(driver.photoUrl),
        
        activityScore = driver.activityScore,
        registrationStatus = driver.registrationStatus.name
    )

    // Вспомогательный метод (компаньон), чтобы генерировать ссылки внутри DTO
    companion object {
        private fun generateUrl(filename: String?): String? {
            if (filename.isNullOrBlank()) return null
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/images/")
                .path(filename)
                .toUriString()
        }
    }
}