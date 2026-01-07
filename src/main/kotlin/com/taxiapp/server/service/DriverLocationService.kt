package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.repository.DriverRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverLocationService(private val driverRepository: DriverRepository) {

    @Transactional
    fun updateLocation(driverId: Long, request: UpdateLocationRequest) {
        if (request.lat == 0.0 && request.lng == 0.0) return
        driverRepository.updateCoordinatesAndTimestamp(driverId, request.lat, request.lng, java.time.LocalDateTime.now())
    }

    // --- НОВЫЙ МЕТОД ---
    @Transactional
    fun clearLocation(driverId: Long) {
        driverRepository.clearCoordinates(driverId)
    }

    @Transactional(readOnly = true)
    fun getOnlineDriversForMap(): List<DriverLocationDto> {
        val threshold = java.time.LocalDateTime.now().minusMinutes(3)
        return driverRepository.findAllActiveOnMap(threshold).map { DriverLocationDto(it) }
    }
}