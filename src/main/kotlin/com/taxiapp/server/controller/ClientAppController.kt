package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.order.CreateOrderRequestDto // <-- ВИПРАВЛЕНО (було CreateOrderRequest)
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.OrderService
import jakarta.validation.Valid
import org.springframework.transaction.annotation.Transactional
import com.taxiapp.server.repository.ClientPromoProgressRepository // <-- Перевірте імпорт
import com.taxiapp.server.repository.SmsVerificationCodeRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@RequestMapping("/api/v1/client") // <-- Рекомендовано додати /api/v1
class ClientAppController(
    private val orderService: OrderService,
    private val orderRepository: TaxiOrderRepository,
    private val userRepository: UserRepository,
    private val clientPromoProgressRepository: com.taxiapp.server.repository.ClientPromoProgressRepository,
    private val smsCodeRepository: com.taxiapp.server.repository.SmsVerificationCodeRepository
) {
    
    // СТВОРЕННЯ ЗАМОВЛЕННЯ
    @PostMapping("/orders") // В Android це @POST("orders") або @POST("client/orders")
    fun createOrder(
        principal: Principal, // 1. Беремо Principal (стандартний Java інтерфейс)
        @Valid @RequestBody request: CreateOrderRequestDto
    ): ResponseEntity<TaxiOrderDto> {
        
        // 2. Дістаємо логін (телефон) з Principal
        val userLogin = principal.name
        
        // 3. Шукаємо нашого реального юзера в базі
        // Спочатку по логіну, потім по телефону (наша розумна логіка)
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }
        
        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Користувач не є клієнтом")
        }
        
        if (user.isBlocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблоковано")
        }
        
        val orderDto = orderService.createOrder(user, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(orderDto)
    }

    // ОТРИМАННЯ ЗАМОВЛЕННЯ
    @GetMapping("/orders/{id}")
    fun getOrder(
        @PathVariable id: Long, 
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) user = userRepository.findByUserPhone(userLogin).orElseThrow()

        val order = orderRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        
        if (order.client.id != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це чуже замовлення")
        }
        
        return ResponseEntity.ok(TaxiOrderDto(order))
    }

    @DeleteMapping("/account")
    @Transactional // Важливо: все або нічого
    fun deleteAccount(principal: Principal): ResponseEntity<MessageResponse> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) user = userRepository.findByUserPhone(userLogin).orElseThrow()

        // 1. Видаляємо прогрес по акціях (ТОЧКА, ДЕ БУЛА ПОМИЛКА)
        val promos = clientPromoProgressRepository.findAllByClientId(user.id)
        clientPromoProgressRepository.deleteAll(promos)

        // 2. Видаляємо (або анонімізуємо) замовлення
        // (Якщо видаляти замовлення не можна для історії, то треба просто відв'язати клієнта, 
        // але для повного видалення акаунту - видаляємо все)
        val orders = orderRepository.findAllByClientId(user.id)
        orderRepository.deleteAll(orders)
        
        // 3. Видаляємо SMS коди (якщо є)
        smsCodeRepository.findByUserPhone(user.userPhone!!).ifPresent { 
            smsCodeRepository.delete(it) 
        }

        // 4. Нарешті видаляємо самого юзера (Клієнта)
        userRepository.delete(user)
        
        return ResponseEntity.ok(MessageResponse("Акаунт та всі дані успішно видалено"))
    }
    
    // --- ДОДАНО: СКАСУВАННЯ ЗАМОВЛЕННЯ ---
    // Це потрібно для кнопки "Скасувати" в Android
    @PostMapping("/orders/{id}/cancel")
    fun cancelOrder(
        @PathVariable id: Long,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) user = userRepository.findByUserPhone(userLogin).orElseThrow()

        val orderDto = orderService.cancelOrder(user, id)
        return ResponseEntity.ok(orderDto)
    }
}