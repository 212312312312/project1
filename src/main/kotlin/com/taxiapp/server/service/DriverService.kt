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

    /**
     * Водитель выходит на линию или уходит с нее
     */
    @Transactional
    fun updateDriverStatus(driver: Driver, request: UpdateDriverStatusRequest): DriverDto {
        
        // Водитель выходит на линию
        if (request.isOnline) {
            if (request.latitude == null || request.longitude == null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Для выхода ONLINE нужны координаты (latitude, longitude)")
            }
            driver.isOnline = true
            driver.currentLatitude = request.latitude
            driver.currentLongitude = request.longitude
        } 
        // Водитель уходит с линии
        else {
            driver.isOnline = false
            driver.currentLatitude = null
            driver.currentLongitude = null
        }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }
}