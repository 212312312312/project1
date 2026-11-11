package com.taxiapp.server.dto.order

import com.taxiapp.server.model.user.Driver
import org.hibernate.Hibernate
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class OrderDriverDto(
    val id: Long,
    val fullName: String,
    val phoneNumber: String,
    val carModel: String?,
    val carColor: String?,
    val carPlateNumber: String?,
    val photoUrl: String?,

    val completedRides: Int,
    val monthsInService: Int,

    val latitude: Double?,
    val longitude: Double?,
    val bearing: Float?,

    // --- НОВЫЕ ПОЛЯ ДЛЯ КЛИЕНТА ---
    val hasMovementIssue: Boolean,
    val hasHearingIssue: Boolean,
    val isDeaf: Boolean,
    val hasSpeechIssue: Boolean,
    val rating: Double,
    val carColorKey: String = "WHITE",
    val carVisualType: String = "STANDARD",
) {
    constructor(driver: Driver) : this(
        id = driver.id!!,
        fullName = driver.fullName ?: "Водій",
        phoneNumber = driver.userPhone ?: "",
        carModel = resolveSafeCar(driver)?.let { "${it.make} ${it.model}" } ?: "Авто",
        carColor = com.taxiapp.server.model.enums.CarColor.fromRawString(resolveSafeCar(driver)?.color).labelUk,
        carPlateNumber = resolveSafeCar(driver)?.plateNumber,
        carColorKey = com.taxiapp.server.model.enums.CarColor.fromRawString(resolveSafeCar(driver)?.color).key,
        carVisualType = com.taxiapp.server.model.enums.CarVisualType.resolveForCar(
            carType = resolveSafeCar(driver)?.carType,
            carModel = resolveSafeCar(driver)?.let { "${it.make} ${it.model}" },
            allowedTariffNames = resolveSafeTariffNames(driver)
        ).name,

        photoUrl = driver.photoUrl?.let { filename ->
            if (filename.startsWith("http")) {
                filename
            } else {
                try {
                    ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/images/")
                        .path(filename)
                        .toUriString()
                } catch (_: Exception) {
                    "/images/$filename"
                }
            }
        },

        completedRides = driver.completedRides,
        rating = driver.rating,

        monthsInService = if (driver.createdAt != null) {
            ChronoUnit.MONTHS.between(driver.createdAt, LocalDateTime.now()).toInt()
        } else {
            0
        },

        latitude = driver.latitude,
        longitude = driver.longitude,
        bearing = driver.bearing,

        // Маппинг для клиента
        hasMovementIssue = driver.hasMovementIssue,
        hasHearingIssue = driver.hasHearingIssue,
        isDeaf = driver.isDeaf,
        hasSpeechIssue = driver.hasSpeechIssue
    )

    companion object {
        private fun resolveSafeCar(driver: Driver): com.taxiapp.server.model.user.Car? {
            return try {
                val c = driver.car
                if (c != null && Hibernate.isInitialized(c)) c else null
            } catch (_: Exception) {
                null
            }
        }

        private fun resolveSafeTariffNames(driver: Driver): List<String> {
            return try {
                when {
                    Hibernate.isInitialized(driver.allowedTariffs) && driver.allowedTariffs.isNotEmpty() ->
                        driver.allowedTariffs.filter { Hibernate.isInitialized(it) }.map { it.name }
                    Hibernate.isInitialized(driver.selectedTariffs) && driver.selectedTariffs.isNotEmpty() ->
                        driver.selectedTariffs.filter { Hibernate.isInitialized(it) }.map { it.name }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}