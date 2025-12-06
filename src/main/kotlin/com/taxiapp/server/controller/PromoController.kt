package com.taxiapp.server.controller

import com.taxiapp.server.dto.promo.ActiveDiscountDto // <-- ПЕРЕКОНАЙТЕСЯ, ЩО ЦЕЙ DTO Є
import com.taxiapp.server.dto.promo.ClientPromoProgressDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.PromoService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.Principal // <-- ВИКОРИСТОВУЄМО Principal

@RestController
@RequestMapping("/api/v1/client/promos")
class PromoController(
    private val promoService: PromoService,
    private val userRepository: UserRepository
) {

    // 1. СПИСОК ЗАВДАНЬ (Виправлено на Principal)
    @GetMapping
    fun getMyPromos(principal: Principal): ResponseEntity<List<ClientPromoProgressDto>> {
        
        val userLogin = principal.name
        
        // Шукаємо користувача (Логін або Телефон)
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        // Перевіряємо тип
        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки для клієнтів")
        }

        // Отримуємо прогрес
        val progressList = promoService.getClientPromos(user)
        
        // Конвертуємо в DTO
        val dtos = progressList.map { p ->
            ClientPromoProgressDto(
                id = p.id,
                title = p.promoTask.title,
                description = p.promoTask.description,
                requiredRides = p.promoTask.requiredRides,
                currentRides = p.currentRidesCount,
                discountPercent = p.promoTask.discountPercent,
                isRewardAvailable = p.isRewardAvailable
            )
        }
        
        return ResponseEntity.ok(dtos)
    }

    // 2. АКТИВНА ЗНИЖКА (Теж переведено на Principal для надійності)
    @GetMapping("/discount")
    fun getActiveDiscount(principal: Principal): ResponseEntity<ActiveDiscountDto> {
        
        val userLogin = principal.name
        
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        if (user !is Client) {
             // Водіям знижки не даємо, повертаємо 0
             return ResponseEntity.ok(ActiveDiscountDto(0.0))
        }
        
        val percent = promoService.getActiveDiscountPercent(user)
        return ResponseEntity.ok(ActiveDiscountDto(percent))
    }
}