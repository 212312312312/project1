package com.taxiapp.server.dto.driver

import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.model.user.Driver
import org.hibernate.Hibernate
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

        rating = driver.rating,

        ratingCount = driver.ratingCount,
        latitude = driver.latitude,
        longitude = driver.longitude,

        car = if (driver.car != null && Hibernate.isInitialized(driver.car)) CarDto(driver.car!!) else null,
        cars = if (Hibernate.isInitialized(driver.cars)) {
            driver.cars.map { CarDto(it) }
        } else {
            listOfNotNull(if (driver.car != null && Hibernate.isInitialized(driver.car)) CarDto(driver.car!!) else null)
        },

        allowedTariffs = if (Hibernate.isInitialized(driver.allowedTariffs)) {
            driver.allowedTariffs.filter { Hibernate.isInitialized(it) }.map { CarTariffDto(it) }
        } else {
            emptyList()
        },
        selectedTariffIds = if (Hibernate.isInitialized(driver.selectedTariffs) && driver.selectedTariffs.isNotEmpty()) {
            driver.selectedTariffs.mapNotNull { it.id }
        } else if (Hibernate.isInitialized(driver.allowedTariffs)) {
            driver.allowedTariffs.mapNotNull { it.id }
        } else {
            emptyList()
        },
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