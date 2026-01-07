package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class DriverService(
    private val driverRepository: DriverRepository
) {
    @Transactional
    fun updateDriverStatus(driver: Driver, request: UpdateDriverStatusRequest): DriverDto {
        if (request.isOnline) {
            // Водитель на линии
            driver.isOnline = true
            driver.currentLatitude = request.latitude
            driver.currentLongitude = request.longitude
            driver.lastUpdate = java.time.LocalDateTime.now() // Обновляем время активности
        } else {
            // Водитель уходит в офлайн
            driver.isOnline = false
            
            // --- ВОЗВРАЩАЕМ ЛОГИКУ УДАЛЕНИЯ С КАРТЫ ---
            // Обнуляем координаты, чтобы он сразу исчез из списка активных на карте
            driver.currentLatitude = null
            driver.currentLongitude = null
            // -----------------------------------------
        }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }
}