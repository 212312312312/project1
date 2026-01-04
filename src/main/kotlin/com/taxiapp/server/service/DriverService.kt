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
            // Водитель выходит на линию -> Обязательно нужны координаты
            if (request.latitude == null || request.longitude == null) {
                // Можно выбрасывать ошибку, а можно просто не обновлять координаты, если их нет
                // Но лучше требовать, чтобы водитель сразу появился в нужной точке
                 throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужны координаты для выхода в онлайн")
            }
            driver.isOnline = true
            driver.currentLatitude = request.latitude
            driver.currentLongitude = request.longitude
        } else {
            // Водитель уходит -> Ставим офлайн, но КООРДИНАТЫ ОСТАВЛЯЕМ (чтобы видеть, где он закончил)
            driver.isOnline = false
            // driver.currentLatitude = null  <-- УДАЛИЛИ ЭТО
            // driver.currentLongitude = null <-- УДАЛИЛИ ЭТО
        }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }
}