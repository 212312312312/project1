package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.repository.DriverRepository // ДОДАНО
import com.taxiapp.server.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal // ДОДАНО
import org.springframework.security.core.userdetails.UserDetails // ДОДАНО
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal
import com.taxiapp.server.model.enums.OrderStatus

@RestController
@RequestMapping("/api/v1/driver/orders")
class DriverOrderController(
    private val orderService: OrderService,
    private val userRepository: UserRepository,
    private val driverRepository: DriverRepository // ДОДАНО В КОНСТРУКТОР
) {

    @PostMapping("/{id}/accept")
    fun acceptOrder(@PathVariable id: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.acceptOrder(driver, id)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/arrive")
    fun driverArrived(@PathVariable id: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.driverArrived(driver, id)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/start")
    fun startTrip(@PathVariable id: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.startTrip(driver, id)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/complete")
    fun completeOrder(@PathVariable id: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.completeOrder(driver, id)
        return ResponseEntity.ok(order)
    }

    @GetMapping("/history")
    fun getOrderHistory(principal: Principal): ResponseEntity<List<TaxiOrderDto>> {
        val driver = getDriverFromPrincipal(principal)
        return ResponseEntity.ok(orderService.findHistoryByDriver(driver))
    }

    @GetMapping("/active")
    fun getActiveOrder(principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val activeOrder = orderService.findActiveOrderByDriver(driver) 
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Активних замовлень немає")
        
        return ResponseEntity.ok(activeOrder)
    }

    @GetMapping("/heatmap")
    fun getHeatmapData(principal: Principal): ResponseEntity<List<HeatmapZoneDto>> {
        // Перевірка, що це водій (можна додати, якщо треба)
        return ResponseEntity.ok(orderService.getDriverHeatmap())
    }

    // Оновлений метод доступних замовлень
    @GetMapping("/available")
    fun getAvailable(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<List<TaxiOrderDto>> {
        val username = userDetails.username
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")

        // Отримуємо вже відфільтровані замовлення (вони вже в форматі DTO)
        val filteredOrders = orderService.getFilteredOrdersForDriver(driver)
        
        // Повертаємо список напряму, бо метод сервісу вже повернув List<TaxiOrderDto>
        return ResponseEntity.ok(filteredOrders)
    }

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