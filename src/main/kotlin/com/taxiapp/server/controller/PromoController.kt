package com.taxiapp.server.controller

import com.taxiapp.server.dto.promo.ActiveDiscountDto
import com.taxiapp.server.dto.promo.ClientPromoProgressDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.PromoService
import com.taxiapp.server.dto.auth.MessageResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

data class ApplyPromoRequest(val code: String)

@RestController
@RequestMapping("/api/v1/client/promos")
class PromoController(
    private val promoService: PromoService,
    private val userRepository: UserRepository
) {

    @GetMapping
    fun getMyPromos(principal: Principal): ResponseEntity<List<ClientPromoProgressDto>> {
        val userLogin = principal.name
        
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки для клієнтів")
        }

        val progressList = promoService.getClientPromos(user)
        
        val dtos = progressList.map { p ->
            ClientPromoProgressDto(
                id = p.id,
                title = p.promoTask.title,
                description = p.promoTask.description ?: "",
                requiredRides = p.promoTask.requiredRides,
                currentRides = p.currentRidesCount,
                discountPercent = p.promoTask.discountPercent,
                isRewardAvailable = p.isRewardAvailable,
                requiredTariffName = p.promoTask.requiredTariff?.name,
                isFullyCompleted = p.isFullyCompleted,
                maxDiscountAmount = p.promoTask.maxDiscountAmount,
                requiredDistanceMeters = p.promoTask.requiredDistanceMeters,
                currentDistanceMeters = p.currentDistanceMeters,
                rewardExpiresAt = p.rewardExpiresAt?.toString() 
            )
        }
        
        val activePlan = promoService.findActiveFreeMinPlan(user)
        val finalDtos = if (activePlan != null) {
            dtos + ClientPromoProgressDto(
                id = -9999L, 
                title = activePlan.title,
                description = activePlan.description ?: "Мінімалка 0 грн! Плати тільки за кілометри.",
                requiredRides = 1,
                currentRides = 0,
                discountPercent = 100.0,
                isRewardAvailable = true,
                requiredTariffName = null,
                isFullyCompleted = false,
                maxDiscountAmount = null,
                requiredDistanceMeters = 0L, 
                currentDistanceMeters = 0L,  
                rewardExpiresAt = activePlan.endDate.toString()
            )
        } else dtos

        return ResponseEntity.ok(finalDtos)
    }

    @GetMapping("/discount")
    fun getActiveDiscount(principal: Principal): ResponseEntity<ActiveDiscountDto> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        if (user !is Client) {
             return ResponseEntity.ok(ActiveDiscountDto(0.0))
        }
        
        val percent = promoService.getActiveDiscountPercent(user)
        val maxAmount = promoService.getActiveMaxDiscountAmount(user)
        
        return ResponseEntity.ok(ActiveDiscountDto(percent, maxAmount))
    }

    @PostMapping("/apply")
    fun applyPromo(
        principal: Principal,
        @RequestBody request: ApplyPromoRequest
    ): ResponseEntity<MessageResponse> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
            user = userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED) }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки для клієнтів")
        }

        try {
            promoService.activatePromoCode(user, request.code)
            return ResponseEntity.ok(MessageResponse("Промокод успішно активовано!"))
        } catch (e: RuntimeException) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(MessageResponse(e.message ?: "Помилка активації"))
        }
    }
}