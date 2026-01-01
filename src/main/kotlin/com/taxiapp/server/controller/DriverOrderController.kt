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
    private val userRepository: UserRepository // <-- Додаємо репозиторій для пошуку
) {

    // Прийняти замовлення
    @PostMapping("/{id}/accept")
    fun acceptOrder(
        @PathVariable id: Long,
        principal: Principal // Використовуємо стандартний Principal
    ): ResponseEntity<TaxiOrderDto> {
        
        val userLogin = principal.name
        
        // Шукаємо користувача (Логін або Телефон)
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }
        
        // Перевіряємо, що це водій
        if (user !is Driver) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки водії можуть приймати замовлення")
        }
        
        // Перевіряємо блокування
        if (user.isBlocked) {
             throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблоковано")
        }
        
        val order = orderService.acceptOrder(user, id)
        return ResponseEntity.ok(order)
    }

    // Завершити замовлення
    @PostMapping("/{id}/complete")
    fun completeOrder(
        @PathVariable id: Long,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        
        val userLogin = principal.name
        
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }
        
        if (user !is Driver) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки водії можуть завершувати замовлення")
        }

        val order = orderService.completeOrder(user, id)
        return ResponseEntity.ok(order)
    }

    @GetMapping("/available")
    fun getAvailableOrders(): ResponseEntity<List<TaxiOrderDto>> {
        // Отримуємо всі замовлення зі статусом REQUESTED
        // (Припускаємо, що у OrderService є метод findAllByStatus, якщо ні - додамо)
        val orders = orderService.findAllByStatus(OrderStatus.REQUESTED)
        return ResponseEntity.ok(orders)
    }
}