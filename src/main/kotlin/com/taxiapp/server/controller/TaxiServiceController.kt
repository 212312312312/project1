package com.taxiapp.server.controller

import com.taxiapp.server.dto.service.TaxiServiceDto
import com.taxiapp.server.model.services.TaxiServiceEntity
import com.taxiapp.server.repository.TaxiServiceRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/admin/services")
class TaxiServiceController(
    private val serviceRepository: TaxiServiceRepository
) {

    // 1. Отримати список активних послуг (для додатка та адмінки)
    @GetMapping
    fun getActiveServices(): List<TaxiServiceDto> {
        return serviceRepository.findAllByIsActiveTrue().map {
            TaxiServiceDto(
                id = it.id!!,
                name = it.name,
                price = it.price,
                evosCode = it.evosCode
            )
        }
    }

    // 2. Створити нову послугу з прив'язкою до коду EvoS
    @PostMapping
    fun createService(@RequestBody dto: TaxiServiceDto): TaxiServiceDto {
        val entity = TaxiServiceEntity(
            name = dto.name,
            price = dto.price,
            evosCode = dto.evosCode?.ifBlank { null }
        )
        val saved = serviceRepository.save(entity)
        return TaxiServiceDto(
            id = saved.id!!,
            name = saved.name,
            price = saved.price,
            evosCode = saved.evosCode
        )
    }

    // 3. Оновити існуючу послугу (редагування назви, ціни та коду EvoS)
    @PutMapping("/{id}")
    fun updateService(@PathVariable id: Long, @RequestBody dto: TaxiServiceDto): TaxiServiceDto {
        val service = serviceRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Послугу не знайдено") }

        val updatedEntity = TaxiServiceEntity(
            id = service.id,
            name = dto.name,
            price = dto.price,
            isActive = service.isActive,
            evosCode = dto.evosCode?.ifBlank { null }
        )
        val saved = serviceRepository.save(updatedEntity)
        return TaxiServiceDto(
            id = saved.id!!,
            name = saved.name,
            price = saved.price,
            evosCode = saved.evosCode
        )
    }

    // 4. Видалити послугу (архівувати)
    @DeleteMapping("/{id}")
    fun deleteService(@PathVariable id: Long): ResponseEntity<Void> {
        val service = serviceRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Послугу не знайдено") }

        service.isActive = false
        serviceRepository.save(service)

        return ResponseEntity.ok().build()
    }
}