package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.repository.DriverRepository // <-- ИМПОРТ
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverLocationService(
    private val driverRepository: DriverRepository
) {
    
    @Transactional(readOnly = true)
    fun getOnlineDriversForMap(): List<DriverLocationDto> {
        return driverRepository.findAllOnline()
            .filter { 
                it.currentLatitude != null && it.currentLongitude != null 
            }
            .map { 
                DriverLocationDto(it) 
            }
    }
}