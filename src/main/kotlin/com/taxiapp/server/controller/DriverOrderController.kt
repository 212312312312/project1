package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@RequestMapping("/api/v1/driver/orders")
class DriverOrderController(
    private val orderService: OrderService,
    private val driverRepository: DriverRepository 
) {

    @PostMapping("/{id}/accept")
    fun acceptOrder(@PathVariable id: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.acceptOrder(driver, id)
        return ResponseEntity.ok(order)
    }

    // --- ДОДАНО: Метод для відхилення (Пропустити) ---
    @PostMapping("/{id}/reject")
    fun rejectOrder(@PathVariable id: Long, principal: Principal): ResponseEntity<Void> {
        val driver = getDriverFromPrincipal(principal)
        orderService.rejectOffer(driver, id)
        return ResponseEntity.ok().build()
    }
    // -------------------------------------------------

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

    @PostMapping("/{id}/cancel")
    fun cancelOrder(@PathVariable id: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.driverCancelOrder(driver, id)
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
        return ResponseEntity.ok(orderService.getDriverHeatmap())
    }

    @GetMapping("/available")
    fun getAvailable(principal: Principal): ResponseEntity<List<TaxiOrderDto>> {
        val driver = getDriverFromPrincipal(principal)
        val filteredOrders = orderService.getFilteredOrdersForDriver(driver)
        return ResponseEntity.ok(filteredOrders)
    }

    private fun getDriverFromPrincipal(principal: Principal): Driver {
        val username = principal.name 
        val driver = driverRepository.findByUserLogin(username)
            ?: driverRepository.findByUserPhone(username)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ заборонено: Ви не водій")

        if (driver.isBlocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблоковано")
        }
        return driver
    }
}