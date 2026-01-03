package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal
import com.taxiapp.server.model.enums.OrderStatus

@RestController
@RequestMapping("/api/v1/driver/orders")
class DriverOrderController(
    private val orderService: OrderService,
    private val userRepository: UserRepository
) {

    // Прийняти замовлення
    @PostMapping("/{id}/accept")
    fun acceptOrder(
        @PathVariable id: Long,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.acceptOrder(driver, id)
        return ResponseEntity.ok(order)
    }

    // НОВЕ: Водій на місці
    @PostMapping("/{id}/arrive")
    fun driverArrived(
        @PathVariable id: Long,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.driverArrived(driver, id)
        return ResponseEntity.ok(order)
    }

    // НОВЕ: Почати поїздку
    @PostMapping("/{id}/start")
    fun startTrip(
        @PathVariable id: Long,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.startTrip(driver, id)
        return ResponseEntity.ok(order)
    }

    // Завершити замовлення
    @PostMapping("/{id}/complete")
    fun completeOrder(
        @PathVariable id: Long,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.completeOrder(driver, id)
        return ResponseEntity.ok(order)
    }

    // Отримати доступні замовлення
    @GetMapping("/available")
    fun getAvailableOrders(): ResponseEntity<List<TaxiOrderDto>> {
        val orders = orderService.findAllByStatus(OrderStatus.REQUESTED)
        return ResponseEntity.ok(orders)
    }

    // --- Допоміжний метод для отримання водія ---
    private fun getDriverFromPrincipal(principal: Principal): Driver {
        val userLogin = principal.name
        
        val user = userRepository.findByUserLogin(userLogin).orElse(null)
            ?: userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }

        if (user !is Driver) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки водії можуть виконувати цю дію")
        }
        
        if (user.isBlocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблоковано")
        }

        return user
    }
}