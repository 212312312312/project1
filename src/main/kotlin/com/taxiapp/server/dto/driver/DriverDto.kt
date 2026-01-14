package com.taxiapp.server.dto.driver

// 1. ВАЖНО: Импорт твоего существующего DTO
import com.taxiapp.server.dto.tariff.CarTariffDto 
import com.taxiapp.server.model.user.Driver
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.LocalDateTime

data class DriverDto(
    val id: Long,
    val phoneNumber: String, 
    val fullName: String,

    // Новые поля
    val email: String?,
    val rnokpp: String?,
    val driverLicense: String?,

    val isOnline: Boolean,
    val isBlocked: Boolean, 
    val tempBlockExpiresAt: LocalDateTime?,
    val currentLatitude: Double?,
    val currentLongitude: Double?,
    
    val car: CarDto?,
    
    // Список полных DTO тарифов
    val allowedTariffs: List<CarTariffDto>,
    
    val photoUrl: String?
) {
    constructor(driver: Driver) : this(
        id = driver.id,
        phoneNumber = driver.userPhone ?: "", 
        fullName = driver.fullName,

        email = driver.email,
        rnokpp = driver.rnokpp,
        driverLicense = driver.driverLicense,

        isOnline = driver.isOnline,
        isBlocked = driver.isBlocked,
        tempBlockExpiresAt = driver.tempBlockExpiresAt,
        currentLatitude = driver.currentLatitude,
        currentLongitude = driver.currentLongitude,
        
        car = driver.car?.let { CarDto(it) }, 
        
        // 2. ИСПРАВЛЕНО: Мы используем твой существующий конструктор CarTariffDto(CarTariff)
        // Раньше тут была ошибка, так как мы передавали (id, name), а твой класс требует все поля.
        allowedTariffs = driver.allowedTariffs.map { CarTariffDto(it) },
        
        photoUrl = driver.photoUrl?.let { filename ->
            ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/images/")
                .path(filename)
                .toUriString()
        }
    )
}