package com.taxiapp.server.dto.order

import com.taxiapp.server.model.user.Driver
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
    
    // --- НОВЫЕ ПОЛЯ ---
    val completedRides: Int,
    val monthsInService: Int
) {
    constructor(driver: Driver) : this(
        id = driver.id,
        fullName = driver.fullName,
        phoneNumber = driver.userPhone ?: "",
        carModel = driver.car?.let { "${it.make} ${it.model}" },
        carColor = driver.car?.color ?: "Колір не вказано",
        carPlateNumber = driver.car?.plateNumber,
        photoUrl = driver.photoUrl?.let { filename ->
            ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/images/")
                .path(filename)
                .toUriString()
        },
        
        // ЗАПОЛНЕНИЕ НОВЫХ ПОЛЕЙ
        completedRides = driver.completedRides,
        // Считаем разницу в месяцах между регистрацией и сейчас
        monthsInService = ChronoUnit.MONTHS.between(driver.createdAt, LocalDateTime.now()).toInt()
    )
}