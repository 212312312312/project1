package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import com.taxiapp.server.model.enums.OrderStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@RequestMapping("/api/v1/driver/orders")
@PreAuthorize("hasAuthority('ROLE_DRIVER')")
class DriverOrderController(
    private val orderService: OrderService,
    private val driverRepository: DriverRepository,
    private val taxiOrderRepository: com.taxiapp.server.repository.TaxiOrderRepository // <-- ДОБАВИЛИ РЕПОЗИТОРИЙ
) {

    // Вспомогательный метод для получения внутреннего Long ID
    private fun getInternalId(uuid: java.util.UUID): Long {
        return taxiOrderRepository.findByUuid(uuid)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
            .id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не має внутрішнього ID")
    }

    @PostMapping("/{id}/accept")
    fun acceptOrder(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.acceptOrder(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/reject")
    fun rejectOrder(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<Void> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        orderService.rejectOffer(driver, internalId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{id}/arrive")
    fun driverArrived(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.driverArrived(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/start")
    fun startTrip(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.startTrip(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/complete")
    fun completeOrder(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.completeOrder(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/confirm")
    fun confirmScheduledOrder(
        @PathVariable id: java.util.UUID,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.confirmScheduledOrder(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/cancel")
    fun cancelOrder(
        @PathVariable id: java.util.UUID, 
        @RequestParam(required = false) reasonId: Long?, 
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.driverCancelOrder(driver, internalId, reasonId)
        return ResponseEntity.ok(order)
    }

    // ДОБАВЬ ЭТОТ НОВЫЙ ЭНДПОИНТ В DriverOrderController.kt

    @GetMapping("/{id}")
    fun getOrderById(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        
        val order = orderService.getOrderById(internalId)
        
        // 🛡️ СТРОГАЯ ЗАЩИТА (IDOR): Запрещаем любой доступ, если ID водителя не совпадает
        if (order.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого замовлення заборонено")
        }
        
        return ResponseEntity.ok(order)
    }

    @GetMapping("/by-internal-id/{internalId}")
    fun getOrderByInternalId(@PathVariable internalId: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val order = orderService.getOrderById(internalId)
        
        // 🛡️ СТРОГАЯ ЗАЩИТА (IDOR): Жестко блокируем попытки перебора чужих ID
        if (order.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого замовлення заборонено")
        }
        
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/waypoint/arrive")
    fun arriveAtWaypoint(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.arriveAtWaypoint(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/waypoint/resume")
    fun resumeTrip(@PathVariable id: java.util.UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.resumeTrip(driver, internalId)
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