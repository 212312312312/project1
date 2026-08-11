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
    val selectedTariffIds: List<Long>,
    val driverLicenseFront: String?,
    val driverLicenseBack: String?,
    val city: String?,
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
    val balance: Double,
    val payoutBalance: Double
) {
    constructor(driver: Driver) : this(
        id = driver.id!!,
        phoneNumber = driver.userPhone ?: "",
        fullName = driver.fullName ?: "",
        email = driver.email,
        rnokpp = driver.rnokpp,
        driverLicense = driver.driverLicense,
        
        driverLicenseFront = generateUrl(driver.driverLicenseFront, isThumbnail = true),
        driverLicenseBack = generateUrl(driver.driverLicenseBack, isThumbnail = true),
        

        // Маппинг новых полей
        hasMovementIssue = driver.hasMovementIssue,
        hasHearingIssue = driver.hasHearingIssue,
        isDeaf = driver.isDeaf,
        hasSpeechIssue = driver.hasSpeechIssue,
        city = driver.city,
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
        selectedTariffIds = if (driver.selectedTariffs.isEmpty()) {
            // Если настроек нет — по умолчанию активны ВСЕ разрешенные диспетчером тарифы
            driver.allowedTariffs.mapNotNull { it.id }
        } else {
            driver.selectedTariffs.mapNotNull { it.id }
        }, // 👈 ВОТ СЮДА ДОБАВЬ ЗАПЯТУЮ!
        photoUrl = generateUrl(driver.photoUrl, isThumbnail = true),
        
        activityScore = driver.activityScore,
        registrationStatus = driver.registrationStatus.name,
        balance = driver.balance,
        payoutBalance = driver.payoutBalance
    )

    companion object {
        fun generateUrl(filename: String?, isThumbnail: Boolean = false): String? {
            if (filename.isNullOrBlank()) return null
            if (filename.startsWith("http://") || filename.startsWith("https://")) return filename
            
            val clean = filename.trimStart('/')
            val rawName = if (clean.contains("/")) clean.substringAfterLast('/') else clean
            val baseName = if (rawName.startsWith("thumb_")) rawName.substringAfter("thumb_") else rawName
            
            val finalName = if (isThumbnail) "thumb_$baseName" else baseName
            return "/images/$finalName"
        }
    }
}