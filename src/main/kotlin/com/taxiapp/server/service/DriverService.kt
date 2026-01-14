package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.DriverRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverService(
    private val driverRepository: DriverRepository
) {
    
    // Метод отримання профілю (використовується додатком водія)
    // Він автоматично підхопить нові фото з оновленого CarDto
    @Transactional(readOnly = true)
    fun getDriverProfile(user: User): DriverDto {
        val driver = driverRepository.findById(user.id)
            .orElseThrow { RuntimeException("Водій не знайдений") }
            
        return DriverDto(driver)
    }

    @Transactional
    fun updateDriverStatus(driver: Driver, request: UpdateDriverStatusRequest): DriverDto {
        if (request.isOnline) {
            driver.isOnline = true
            driver.currentLatitude = request.latitude
            driver.currentLongitude = request.longitude
            driver.lastUpdate = java.time.LocalDateTime.now()
        } else {
            driver.isOnline = false
            driver.currentLatitude = null
            driver.currentLongitude = null
        }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }
}