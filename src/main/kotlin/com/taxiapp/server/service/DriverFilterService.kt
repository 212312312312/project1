package com.taxiapp.server.service

import com.taxiapp.server.dto.filter.CreateFilterRequest
import com.taxiapp.server.dto.filter.DriverFilterDto
import com.taxiapp.server.dto.filter.UpdateFilterModeRequest
import com.taxiapp.server.model.filter.DriverFilter
import com.taxiapp.server.repository.DriverFilterRepository
import com.taxiapp.server.repository.DriverRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class DriverFilterService(
    private val filterRepository: DriverFilterRepository,
    private val driverRepository: DriverRepository
) {

    private fun getCurrentDriverId(): Long {
        val username = SecurityContextHolder.getContext().authentication.name
        val driver = driverRepository.findByUserLogin(username)
            ?: driverRepository.findByUserPhone(username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")
        return driver.id!!
    }

    private fun checkActiveFiltersLimit(driverId: Long, excludeFilterId: Long? = null) {
        val activeFilters = filterRepository.findAllByDriverId(driverId)
            .filter { it.isActive }
            .filter { it.id != excludeFilterId }
        
        if (activeFilters.size >= 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Максимум 3 активних фільтри")
        }
    }

    // ВАЖНО: Валидация несовместимых режимов
    private fun validateModes(isAuto: Boolean, isCycle: Boolean) {
        if (isAuto && isCycle) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Неможливо включити Авто і Цикл одночасно")
        }
    }

    @Transactional(readOnly = true)
    fun getMyFilters(): List<DriverFilterDto> {
        return filterRepository.findAllByDriverId(getCurrentDriverId()).map { mapToDto(it) }
    }

    @Transactional
    fun createFilter(req: CreateFilterRequest): DriverFilterDto {
        val driverId = getCurrentDriverId()
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено") }

        // Визначаємо isActive: якщо хоч щось включено -> фільтр активний
        val isActive = req.isEther || req.isAuto || req.isCycle
        
        if (isActive) {
            checkActiveFiltersLimit(driverId)
            validateModes(req.isAuto, req.isCycle)
        }

        val filter = DriverFilter(
            driver = driver,
            name = req.name,
            isActive = isActive,
            isEther = req.isEther,
            isAuto = req.isAuto,   
            isCycle = req.isCycle, 
            
            fromType = req.fromType,
            fromDistance = req.fromDistance,
            fromSectors = req.fromSectors.toMutableList(),
            toSectors = req.toSectors.toMutableList(),
            tariffType = req.tariffType,
            minPrice = req.minPrice,
            minPricePerKm = req.minPricePerKm,
            complexMinPrice = req.complexMinPrice,
            complexKmInMin = req.complexKmInMin,
            complexPriceKmCity = req.complexPriceKmCity,
            complexPriceKmSuburbs = req.complexPriceKmSuburbs,
            paymentType = req.paymentType
        )
        return mapToDto(filterRepository.save(filter))
    }

    @Transactional
    fun updateFilterMode(id: Long, req: UpdateFilterModeRequest): DriverFilterDto {
        val filter = filterRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Фільтр не знайдено") }

        if (filter.driver.id != getCurrentDriverId()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваш фільтр")
        }

        // ВАЛИДАЦИЯ: Нельзя Auto и Cycle одновременно
        if (req.isAuto && req.isCycle) {
             throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Авто і Цикл не можуть працювати разом")
        }

        // ЛОГИКА: Фільтр активний, якщо увімкнено хоч один режим
        val shouldBeActive = req.isEther || req.isAuto || req.isCycle

        // Якщо фільтр стає активним (а був вимкнений) — перевіряємо ліміт (макс 3)
        if (shouldBeActive && !filter.isActive) {
            checkActiveFiltersLimit(getCurrentDriverId(), excludeFilterId = id)
        }

        filter.isActive = shouldBeActive // Сервер сам ставить активність
        filter.isEther = req.isEther
        filter.isAuto = req.isAuto
        filter.isCycle = req.isCycle

        return mapToDto(filterRepository.save(filter))
    }

    @Transactional
    fun updateFilter(id: Long, req: CreateFilterRequest): DriverFilterDto {
        val filter = filterRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Фільтр не знайдено") }

        if (filter.driver.id != getCurrentDriverId()) throw ResponseStatusException(HttpStatus.FORBIDDEN)

        filter.name = req.name
        filter.fromType = req.fromType
        filter.fromDistance = req.fromDistance
        filter.fromSectors.clear()
        filter.fromSectors.addAll(req.fromSectors)
        filter.toSectors.clear()
        filter.toSectors.addAll(req.toSectors)
        filter.tariffType = req.tariffType
        filter.minPrice = req.minPrice
        filter.minPricePerKm = req.minPricePerKm
        filter.complexMinPrice = req.complexMinPrice
        filter.complexKmInMin = req.complexKmInMin
        filter.complexPriceKmCity = req.complexPriceKmCity
        filter.complexPriceKmSuburbs = req.complexPriceKmSuburbs
        filter.paymentType = req.paymentType
        
        // Режими
        val isActive = req.isEther || req.isAuto || req.isCycle
        if (isActive) validateModes(req.isAuto, req.isCycle)
        
        filter.isActive = isActive
        filter.isEther = req.isEther
        filter.isAuto = req.isAuto
        filter.isCycle = req.isCycle

        return mapToDto(filterRepository.save(filter))
    }

    // toggleFilter, disableAll, deleteFilter залишаються без змін...
    @Transactional
    fun toggleFilter(id: Long) {
        val filter = filterRepository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (filter.driver.id != getCurrentDriverId()) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        if (!filter.isActive) checkActiveFiltersLimit(getCurrentDriverId(), id)
        
        filter.isActive = !filter.isActive
        // При простому тогл (якщо такий буде) - режими не змінюємо
    }
    
    @Transactional
    fun disableAll() {
        val filters = filterRepository.findAllByDriverId(getCurrentDriverId())
        filters.forEach { it.isActive = false }
    }

    @Transactional
    fun deleteFilter(id: Long) {
        if (!filterRepository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        filterRepository.deleteById(id)
    }

    private fun mapToDto(f: DriverFilter) = DriverFilterDto(
        id = f.id!!,
        name = f.name,
        isActive = f.isActive,
        isEther = f.isEther,
        isAuto = f.isAuto,
        isCycle = f.isCycle,
        description = buildDescription(f),
        fromType = f.fromType,
        fromDistance = f.fromDistance,
        fromSectors = f.fromSectors,
        toSectors = f.toSectors,
        tariffType = f.tariffType,
        minPrice = f.minPrice,
        minPricePerKm = f.minPricePerKm,
        complexMinPrice = f.complexMinPrice,
        complexKmInMin = f.complexKmInMin,
        complexPriceKmCity = f.complexPriceKmCity,
        complexPriceKmSuburbs = f.complexPriceKmSuburbs,
        paymentType = f.paymentType
    )

    private fun buildDescription(f: DriverFilter): String {
        val modes = mutableListOf<String>()
        if (f.isEther) modes.add("Ефір")
        if (f.isAuto) modes.add("Авто")
        if (f.isCycle) modes.add("Цикл")
        
        val modeStr = if (modes.isEmpty()) "Налаштування" else modes.joinToString("+")
        val from = if (f.fromType == "DISTANCE") "R${f.fromDistance}" else "Сектори"
        return "$modeStr • $from"
    }
}