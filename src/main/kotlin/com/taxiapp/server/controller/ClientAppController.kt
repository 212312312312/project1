package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientPromoProgressRepository
import com.taxiapp.server.repository.ClientRepository // <-- ВАЖНО
import com.taxiapp.server.repository.SmsVerificationCodeRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication // <-- ВАЖНО
import org.springframework.security.core.userdetails.UserDetails // <-- ВАЖНО
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
    private val clientRepository: ClientRepository, // <-- ДОБАВЛЕНО В КОНСТРУКТОР
    private val clientPromoProgressRepository: ClientPromoProgressRepository,
    private val smsCodeRepository: SmsVerificationCodeRepository,
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

        val promos = clientPromoProgressRepository.findAllByClientId(user.id)
        clientPromoProgressRepository.deleteAll(promos)

        val orders = orderRepository.findAllByClientId(user.id)
        orderRepository.deleteAll(orders)
        
        smsCodeRepository.findByUserPhone(user.userPhone!!).ifPresent { 
            smsCodeRepository.delete(it) 
        }

        userRepository.delete(user)
        
        return ResponseEntity.ok(MessageResponse("Акаунт та всі дані успішно видалено"))
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
        
        // Теперь clientRepository доступен
        val client = clientRepository.findByUserPhone(userDetails.username)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        // Используем orderRepository (он же TaxiOrderRepository), который объявлен в конструкторе
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