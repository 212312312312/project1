package com.taxiapp.server.controller

import com.taxiapp.server.dto.promo.CreatePromoCodeRequest
import com.taxiapp.server.dto.promo.PromoCodeDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.PromoCodeService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@RequestMapping("/api/v1")
class PromoCodeController(
    private val promoCodeService: PromoCodeService,
    private val userRepository: UserRepository
) {

    // --- ДЛЯ АДМИНА ---
    
    // 1. Створення (POST)
    @PostMapping("/admin/promocodes")
    fun createPromo(@RequestBody req: CreatePromoCodeRequest): ResponseEntity<Void> {
        promoCodeService.createPromoCode(req)
        return ResponseEntity.ok().build()
    }

    // 2. Отримання списку (GET) <-- ОСЬ ЦЬОГО НЕ ВИСТАЧАЛО
    @GetMapping("/admin/promocodes")
    fun getAllPromos(): ResponseEntity<List<PromoCodeDto>> {
        return ResponseEntity.ok(promoCodeService.getAllPromoCodes())
    }

    // --- ДЛЯ КЛИЕНТА ---
    
    @PostMapping("/promocodes/activate")
    fun activatePromo(
        principal: Principal,
        @RequestParam code: String
    ): ResponseEntity<Void> {
        val userLogin = principal.name
        
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED) }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки клієнти можуть використовувати промокоди")
        }

        promoCodeService.activatePromoCode(user, code)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/admin/promocodes/{id}")
    fun deletePromo(@PathVariable id: Long): ResponseEntity<Void> {
        promoCodeService.deletePromoCode(id)
        return ResponseEntity.noContent().build()
    }
}