package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.DriverSearchSettingsDto
import com.taxiapp.server.dto.driver.DriverSearchStateDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.SectorRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

@Service
class DriverService(
    private val driverRepository: DriverRepository,
    private val sectorRepository: SectorRepository
) {
    
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
            driver.lastUpdate = LocalDateTime.now()
        } else {
            // При виході в ОФЛАЙН - скидаємо режим в MANUAL
            driver.isOnline = false
            driver.currentLatitude = null
            driver.currentLongitude = null
            driver.searchMode = DriverSearchMode.MANUAL 
        }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }

    // --- НОВІ МЕТОДИ ДЛЯ ЛАНЦЮГА ТА ДОДОМУ ---

    @Transactional(readOnly = true)
    fun getSearchState(driver: Driver): DriverSearchStateDto {
        checkAndResetHomeLimit(driver)
        
        val sectorNames = driver.homeSectors.joinToString(", ") { it.name }
        // mapNotNull гарантує, що ми отримаємо List<Long>, а не List<Long?>
        val sectorIds = driver.homeSectors.mapNotNull { it.id }

        return DriverSearchStateDto(
            mode = driver.searchMode,
            radius = driver.searchRadius,
            homeSectorIds = sectorIds,
            homeSectorNames = if (sectorNames.isNotEmpty()) sectorNames else null,
            homeRidesLeft = driver.homeRidesLeft
        )
    }

    @Transactional
    fun updateSearchSettings(driver: Driver, settings: DriverSearchSettingsDto): DriverSearchStateDto {
        // 1. Перевірка Активності
        if (settings.mode != DriverSearchMode.MANUAL && driver.activityScore <= 400) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Низька активність (<400). Автопошук заблоковано.")
        }

        // 2. Логіка для режиму "Додому"
        checkAndResetHomeLimit(driver) 

        if (settings.mode == DriverSearchMode.HOME) {
            if (driver.homeRidesLeft <= 0) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Вичерпано ліміт поїздок 'Додому' на сьогодні.")
            }
            
            // ВАЛІДАЦІЯ СПИСКУ СЕКТОРІВ
            val ids = settings.homeSectorIds
            if (ids.isNullOrEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Оберіть хоча б один сектор.")
            }
            if (ids.size > 30) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Максимум 30 секторів.")
            }

            // Знаходимо сектори в базі
            val sectors = sectorRepository.findAllById(ids)
            if (sectors.isEmpty()) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Сектори не знайдено")
            }
            
            // Оновлюємо список
            driver.homeSectors.clear()
            driver.homeSectors.addAll(sectors)
        }

        // 3. Збереження налаштувань
        driver.searchMode = settings.mode
        driver.searchRadius = settings.radius.coerceIn(0.5, 30.0) 
        
        driverRepository.save(driver)

        // Формуємо відповідь
        val sectorNames = driver.homeSectors.joinToString(", ") { it.name }
        val sectorIds = driver.homeSectors.mapNotNull { it.id }

        return DriverSearchStateDto(
            mode = driver.searchMode,
            radius = driver.searchRadius,
            homeSectorIds = sectorIds,
            homeSectorNames = if (sectorNames.isNotEmpty()) sectorNames else null,
            homeRidesLeft = driver.homeRidesLeft
        )
    }

    private fun checkAndResetHomeLimit(driver: Driver) {
        val now = LocalDateTime.now()
        val lastUsage = driver.lastHomeUsageDate
        
        if (lastUsage == null || lastUsage.toLocalDate() != now.toLocalDate()) {
            driver.homeRidesLeft = 2
            driver.lastHomeUsageDate = now
            driverRepository.save(driver)
        }
    }
    
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    fun resetAllDailyLimits() {
        driverRepository.resetAllHomeLimits()
    }
}