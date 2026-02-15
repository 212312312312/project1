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
    
    val driverLicenseFront: String?,
    val driverLicenseBack: String?,

    // --- НОВЫЕ ПОЛЯ (ИНВАЛИДНОСТЬ) ---
    val hasMovementIssue: Boolean,
    val hasHearingIssue: Boolean,
    val isDeaf: Boolean,
    val hasSpeechIssue: Boolean,
    // --------------------------------

    val isOnline: Boolean,
    val isBlocked: Boolean,
    val tempBlockExpiresAt: LocalDateTime?,
    
    // !!! ИСПРАВЛЕНИЕ ТУТ: Убрали "= driver.rating"
    val rating: Double,
    
    val ratingCount: Int,
    val latitude: Double?, 
    val longitude: Double?,
    
    val car: CarDto?,         
    val cars: List<CarDto>?, 
    
    val allowedTariffs: List<CarTariffDto>,
    val photoUrl: String?,    
    val activityScore: Int,
    val registrationStatus: String,
    val balance: Double
) {
    constructor(driver: Driver) : this(
        id = driver.id!!,
        phoneNumber = driver.userPhone ?: "",
        fullName = driver.fullName ?: "",
        email = driver.email,
        rnokpp = driver.rnokpp,
        driverLicense = driver.driverLicense,
        
        driverLicenseFront = generateUrl(driver.driverLicenseFront),
        driverLicenseBack = generateUrl(driver.driverLicenseBack),

        // Маппинг новых полей
        hasMovementIssue = driver.hasMovementIssue,
        hasHearingIssue = driver.hasHearingIssue,
        isDeaf = driver.isDeaf,
        hasSpeechIssue = driver.hasSpeechIssue,

        isOnline = driver.isOnline,
        isBlocked = driver.isBlocked,
        tempBlockExpiresAt = driver.tempBlockExpiresAt,
        
        // Значение присваивается здесь, поэтому в шапке класса дефолт не нужен
        rating = driver.rating,
        
        ratingCount = driver.ratingCount,
        latitude = driver.latitude,
        longitude = driver.longitude,
        
        car = driver.car?.let { CarDto(it) },
        cars = driver.cars.map { CarDto(it) },

        allowedTariffs = driver.allowedTariffs.map { CarTariffDto(it) },
        
        photoUrl = generateUrl(driver.photoUrl),
        
        activityScore = driver.activityScore,
        registrationStatus = driver.registrationStatus.name,
        balance = driver.balance
    )

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