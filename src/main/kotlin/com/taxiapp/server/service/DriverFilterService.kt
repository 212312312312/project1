package com.taxiapp.server.service

import com.taxiapp.server.dto.filter.CreateFilterRequest
import com.taxiapp.server.dto.filter.DriverFilterDto
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
        // Отримуємо "ім'я" з токена (це або login, або phone)
        val username = SecurityContextHolder.getContext().authentication.name
        
        // Шукаємо спочатку за логіном, якщо не знайшли — за телефоном
        val driver = driverRepository.findByUserLogin(username)
            ?: driverRepository.findByUserPhone(username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")
            
        return driver.id!! // id успадковується від User
    }

    @Transactional(readOnly = true)
    fun getMyFilters(): List<DriverFilterDto> {
        return filterRepository.findAllByDriverId(getCurrentDriverId()).map { mapToDto(it) }
    }

    @Transactional
    fun createFilter(req: CreateFilterRequest): DriverFilterDto {
        val driver = driverRepository.findById(getCurrentDriverId())
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено") }

        val filter = DriverFilter(
            driver = driver,
            name = req.name,
            fromType = req.fromType,
            fromDistance = req.fromDistance,
            fromSectors = req.fromSectors.toMutableList(),
            toSectors = req.toSectors.toMutableList(),
            tariffType = req.tariffType,
            
            // Простий тариф
            minPrice = req.minPrice,
            minPricePerKm = req.minPricePerKm,
            
            // Складний тариф (Оновлено)
            complexMinPrice = req.complexMinPrice,
            complexKmInMin = req.complexKmInMin,           // Нове поле
            complexPriceKmCity = req.complexPriceKmCity,
            complexPriceKmSuburbs = req.complexPriceKmSuburbs, // Нове поле
            
            paymentType = req.paymentType
        )
        return mapToDto(filterRepository.save(filter))
    }

    @Transactional
    fun toggleFilter(id: Long) {
        val filter = filterRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Фільтр не знайдено") }
        filter.isActive = !filter.isActive
    }

    @Transactional
    fun disableAll() {
        val filters = filterRepository.findAllByDriverId(getCurrentDriverId())
        filters.forEach { it.isActive = false }
    }

    @Transactional
    fun deleteFilter(id: Long) {
        if (!filterRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Фільтр не знайдено")
        }
        filterRepository.deleteById(id)
    }

    private fun mapToDto(f: DriverFilter) = DriverFilterDto(
        id = f.id!!,
        name = f.name,
        isActive = f.isActive,
        description = buildDescription(f),
        fromType = f.fromType,
        fromDistance = f.fromDistance,
        fromSectors = f.fromSectors,
        toSectors = f.toSectors,
        tariffType = f.tariffType,
        minPrice = f.minPrice,
        minPricePerKm = f.minPricePerKm,
        complexMinPrice = f.complexMinPrice,
        complexKmInMin = f.complexKmInMin,           // Додано в DTO
        complexPriceKmCity = f.complexPriceKmCity,
        complexPriceKmSuburbs = f.complexPriceKmSuburbs, // Додано в DTO
        paymentType = f.paymentType
    )

    private fun buildDescription(f: DriverFilter): String {
        val from = if (f.fromType == "DISTANCE") "Радіус ${f.fromDistance}км" else "Сектори (${f.fromSectors.size})"
        val payment = when(f.paymentType) { 
            "CASH" -> "Готівка" 
            "CARD" -> "Картка" 
            else -> "Будь-яка" 
        }
        val tariff = if (f.tariffType == "SIMPLE") "Простий" else "Складний"
        return "$from • $payment • $tariff"
    }
}   