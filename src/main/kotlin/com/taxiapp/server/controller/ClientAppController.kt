package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientPromoProgressRepository
import com.taxiapp.server.repository.ClientRepository
import com.taxiapp.server.repository.SmsVerificationCodeRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.repository.RefreshTokenRepository // <-- ИСПРАВЛЕНО: добавлен импорт
import com.taxiapp.server.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal
import com.taxiapp.server.dto.service.TaxiServiceDto
import com.taxiapp.server.repository.TaxiServiceRepository

@RestController
@RequestMapping("/api/v1/client")
class ClientAppController(
    private val orderService: OrderService,
    private val orderRepository: TaxiOrderRepository,
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val clientPromoProgressRepository: ClientPromoProgressRepository,
    private val smsCodeRepository: SmsVerificationCodeRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val taxiServiceRepository: TaxiServiceRepository
) {
    
    // СТВОРЕННЯ ЗАМОВЛЕННЯ
    @PostMapping("/orders")
    fun createOrder(
        principal: Principal,
        @Valid @RequestBody request: CreateOrderRequestDto
    ): ResponseEntity<TaxiOrderDto> {
        
        val userLogin = principal.name
        
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
    @Transactional
    fun deleteAccount(principal: Principal): ResponseEntity<MessageResponse> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) user = userRepository.findByUserPhone(userLogin).orElseThrow()

        // 1. Удаляем промо
        val promos = clientPromoProgressRepository.findAllByClientId(user.id)
        clientPromoProgressRepository.deleteAll(promos)

        // 2. Находим фейкового клиента (которого мы создали в SQL)
        val deletedUserDummy = clientRepository.findById(999999L).orElseThrow { 
            RuntimeException("Системный аккаунт 'Deleted Account' не найден в БД! Выполни SQL скрипт.") 
        }

        // 3. Перевешиваем заказы на заглушку
        val orders = orderRepository.findAllByClientId(user.id)
        orders.forEach { order ->
            order.client = deletedUserDummy
        }
        orderRepository.saveAll(orders)
        
        // 4. Удаляем SMS
        smsCodeRepository.findByUserPhone(user.userPhone!!).ifPresent { 
            smsCodeRepository.delete(it) 
        }

        // 5. Удаляем токены.
        val userTokens = refreshTokenRepository.findAll().filter { it.user.id == user.id }
        refreshTokenRepository.deleteAll(userTokens)

        // 6. Удаляем самого пользователя
        userRepository.delete(user)
        
        return ResponseEntity.ok(MessageResponse("Акаунт успішно видалено"))
    }
    
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

    // ИСТОРИЯ ЗАКАЗОВ
    @GetMapping("/orders")
    fun getClientOrders(authentication: Authentication): ResponseEntity<List<TaxiOrderDto>> {
        val userDetails = authentication.principal as UserDetails
        
        val client = clientRepository.findByUserPhone(userDetails.username)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        val orders = orderRepository.findAllByClientOrderByCreatedAtDesc(client)
        
        val dtos = orders.map { TaxiOrderDto(it) }
        
        return ResponseEntity.ok(dtos)
    }

    @GetMapping("/services")
    fun getActiveServices(): ResponseEntity<List<TaxiServiceDto>> {
        val services = taxiServiceRepository.findAllByIsActiveTrue().map {
            TaxiServiceDto(it.id!!, it.name, it.price)
        }
        return ResponseEntity.ok(services)
    }
}