package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/api/v1/driver/orders")
@PreAuthorize("hasAuthority('ROLE_DRIVER')")
class DriverOrderController(
    private val orderService: OrderService,
    private val driverRepository: DriverRepository,
    private val taxiOrderRepository: TaxiOrderRepository
) {

    private fun getInternalId(uuid: UUID): Long {
        return taxiOrderRepository.findByUuid(uuid)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
            .id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не має внутрішнього ID")
    }

    @PostMapping("/{id}/accept")
    fun acceptOrder(@PathVariable id: UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.acceptOrder(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/reject")
    fun rejectOrder(@PathVariable id: UUID, principal: Principal): ResponseEntity<Void> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        orderService.rejectOffer(driver, internalId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{id}/arrive")
    fun driverArrived(@PathVariable id: UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.driverArrived(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/start")
    fun startTrip(@PathVariable id: UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.startTrip(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/complete")
    fun completeOrder(@PathVariable id: UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.completeOrder(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/confirm")
    fun confirmScheduledOrder(
        @PathVariable id: UUID,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.confirmScheduledOrder(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/cancel")
    fun cancelOrder(
        @PathVariable id: UUID,
        @RequestParam(required = false) reasonId: Long?,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.driverCancelOrder(driver, internalId, reasonId)
        return ResponseEntity.ok(order)
    }

    @GetMapping("/{id}")
    fun getOrderById(@PathVariable id: UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val orderEntity = taxiOrderRepository.findByUuid(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        return ResponseEntity.ok(validateAndMapOrderForDriver(orderEntity, driver))
    }

    @GetMapping("/by-internal-id/{internalId}")
    fun getOrderByInternalId(@PathVariable internalId: Long, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val orderEntity = orderService.getOrderEntityById(internalId)

        return ResponseEntity.ok(validateAndMapOrderForDriver(orderEntity, driver))
    }

    private fun validateAndMapOrderForDriver(
        orderEntity: com.taxiapp.server.model.order.TaxiOrder,
        driver: Driver
    ): TaxiOrderDto {
        val isAssignedDriver = orderEntity.driver?.id == driver.id
        val isOfferedDriver = orderEntity.status == OrderStatus.OFFERING && orderEntity.offeredDriver?.id == driver.id
        val isPublicEtherOrder = orderEntity.status == OrderStatus.REQUESTED ||
            (orderEntity.status == OrderStatus.SCHEDULED && orderEntity.driver == null && orderEntity.offeredDriver == null)

        if (!isAssignedDriver && !isOfferedDriver && !isPublicEtherOrder) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого замовлення заборонено")
        }

        val dto = TaxiOrderDto(orderEntity)
        return if (isAssignedDriver) {
            dto
        } else {
            // До фактического принятия заказа телефон клиента скрыт (пустая строка для non-null String)
            dto.copy(client = dto.client.copy(phoneNumber = ""))
        }
    }

    @PostMapping("/{id}/waypoint/arrive")
    fun arriveAtWaypoint(@PathVariable id: UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val driver = getDriverFromPrincipal(principal)
        val internalId = getInternalId(id)
        val order = orderService.arriveAtWaypoint(driver, internalId)
        return ResponseEntity.ok(order)
    }

    @PostMapping("/{id}/waypoint/resume")
    fun resumeTrip(@PathVariable id: UUID, principal: Principal): ResponseEntity<TaxiOrderDto> {
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
        val auth = principal as? Authentication
        val cachedDriver = auth?.principal as? Driver
        val driver = cachedDriver ?: run {
            val username = principal.name
            driverRepository.findByUserLogin(username)
                ?: driverRepository.findByUserPhone(username)
                ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ заборонено: Ви не водій")
        }

        if (driver.isBlocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблоковано")
        }
        return driver
    }
}