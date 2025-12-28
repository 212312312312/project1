package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.service.OrderAdminService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/orders")
class OrderAdminController(
    private val orderAdminService: OrderAdminService
) {

    // "Активные заказы" (Real-time update 10 сек)
    @GetMapping("/active")
    fun getActiveOrders(): ResponseEntity<List<TaxiOrderDto>> {
        return ResponseEntity.ok(orderAdminService.getActiveOrders())
    }

    // "Архив заказов"
    @GetMapping("/archive")
    fun getArchivedOrders(): ResponseEntity<List<TaxiOrderDto>> {
        return ResponseEntity.ok(orderAdminService.getArchivedOrders())
    }

    // Поиск по Архиву (по номеру клиента или водителя)
    @GetMapping("/archive/search")
    fun searchArchive(@RequestParam phone: String): ResponseEntity<List<TaxiOrderDto>> {
        return ResponseEntity.ok(orderAdminService.searchArchive(phone))
    }
    
    // Отменить заказ
    @PostMapping("/{id}/cancel")
    fun cancelOrder(@PathVariable id: Long): ResponseEntity<TaxiOrderDto> {
        return ResponseEntity.ok(orderAdminService.cancelOrder(id))
    }

    // Назначить водителя на заказ
    @PostMapping("/{id}/assign")
    fun assignDriver(
        @PathVariable id: Long,
        @RequestParam driverId: Long 
    ): ResponseEntity<TaxiOrderDto> {
        return ResponseEntity.ok(orderAdminService.assignDriverToOrder(id, driverId))
    }
}