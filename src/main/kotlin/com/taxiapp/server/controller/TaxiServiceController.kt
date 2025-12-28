package com.taxiapp.server.controller

import com.taxiapp.server.dto.service.TaxiServiceDto
import com.taxiapp.server.model.services.TaxiServiceEntity
import com.taxiapp.server.repository.TaxiServiceRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api/v1/admin/services")
class TaxiServiceController(
    private val serviceRepository: TaxiServiceRepository
) {

    // 1. Получить список активных услуг (для приложения и админки)
    @GetMapping
    fun getActiveServices(): List<TaxiServiceDto> {
        return serviceRepository.findAllByIsActiveTrue().map {
            TaxiServiceDto(it.id!!, it.name, it.price)
        }
    }

    // 2. Создать услугу (для админки)
    @PostMapping
    fun createService(@RequestBody dto: TaxiServiceDto): TaxiServiceDto {
        val entity = TaxiServiceEntity(
            name = dto.name,
            price = dto.price
        )
        val saved = serviceRepository.save(entity)
        return TaxiServiceDto(saved.id!!, saved.name, saved.price)
    }

    // 3. Удалить услугу (для админки)
    @DeleteMapping("/{id}")
    fun deleteService(@PathVariable id: Long): ResponseEntity<Void> {
        // 1. Ищем услугу
        val service = serviceRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Послугу не знайдено") }

        // 2. Вместо удаления (delete) делаем её неактивной (архивируем)
        service.isActive = false
        serviceRepository.save(service)

        // 3. Возвращаем ОК
        return ResponseEntity.ok().build()
    }
}