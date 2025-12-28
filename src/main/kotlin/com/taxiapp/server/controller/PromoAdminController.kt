package com.taxiapp.server.controller

import com.taxiapp.server.dto.promo.CreatePromoRequest
import com.taxiapp.server.model.promo.PromoTask
import com.taxiapp.server.repository.CarTariffRepository
import com.taxiapp.server.repository.PromoTaskRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/promos")
class PromoAdminController(
    private val promoRepository: PromoTaskRepository,
    private val tariffRepository: CarTariffRepository
) {

    @GetMapping
    fun getAllPromos(): ResponseEntity<List<PromoTask>> {
        return ResponseEntity.ok(promoRepository.findAll())
    }

    @PostMapping
    fun createPromo(@RequestBody request: CreatePromoRequest): ResponseEntity<PromoTask> {
        
        val tariff = request.requiredTariffId?.let { 
            tariffRepository.findById(it).orElse(null) 
        }

        // Конвертація КМ -> Метри (50 км -> 50000 м)
        val distMeters = (request.requiredDistanceKm * 1000).toLong()
        
        // ЗАХИСТ: Якщо задана дистанція, примусово ставимо requiredRides = 0,
        // щоб завдання не виконалось випадково після 1 поїздки.
        val finalRequiredRides = if (distMeters > 0) 0 else request.requiredRides

        val task = PromoTask(
            title = request.title,
            description = request.description,
            requiredRides = finalRequiredRides, 
            discountPercent = request.discountPercent,
            requiredTariff = tariff,
            isActive = true,
            isOneTime = request.isOneTime,
            maxDiscountAmount = request.maxDiscountAmount,
            requiredDistanceMeters = distMeters,
            activeDaysDuration = request.activeDaysDuration
        )
        
        println(">>> ADMIN: Створено акцію '${task.title}'. Дистанція: $distMeters м, Поїздок: $finalRequiredRides")
        
        return ResponseEntity.ok(promoRepository.save(task))
    }

    @DeleteMapping("/{id}")
    fun deletePromo(@PathVariable id: Long): ResponseEntity<Void> {
        promoRepository.deleteById(id)
        return ResponseEntity.ok().build()
    }
}