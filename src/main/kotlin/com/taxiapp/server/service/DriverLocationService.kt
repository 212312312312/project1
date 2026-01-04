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
        // Пропускаем нули
        if (request.lat == 0.0 && request.lng == 0.0) {
            return
        }

        // БЫЛО (Медленно): 
        // val driver = driverRepository.findById(driverId)... driverRepository.save(driver)

        // СТАЛО (Быстро):
        // Бьем сразу в базу. Координаты обновятся мгновенно, даже если водитель не нажал "Онлайн".
        driverRepository.updateCoordinates(driverId, request.lat, request.lng)
    }

    @Transactional(readOnly = true)
    fun getOnlineDriversForMap(): List<DriverLocationDto> {
        return driverRepository.findAllWithCoordinates()
            .map { DriverLocationDto(it) }
    }
}