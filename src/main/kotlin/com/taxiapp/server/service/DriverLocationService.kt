package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.repository.DriverRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverLocationService(
    private val driverRepository: DriverRepository
) {
    @Transactional
    fun updateLocation(driverId: Long, request: UpdateLocationRequest) {
        val driver = driverRepository.findById(driverId).orElse(null) ?: return
        driver.currentLatitude = request.lat
        driver.currentLongitude = request.lng
        driverRepository.save(driver)
    }

    @Transactional(readOnly = true)
    fun getOnlineDriversForMap(): List<DriverLocationDto> {
        // Берем всех с координатами
        return driverRepository.findAllWithCoordinates()
            .map { DriverLocationDto(it) }
    }
}