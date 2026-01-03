package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.service.OrderAdminService
import com.taxiapp.server.service.OrderService // <--- Не забудьте этот импорт!
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/orders")
class OrderAdminController(
    private val orderAdminService: OrderAdminService,
    private val orderService: OrderService // <--- ДОБАВИЛИ СЮДА
) {

    // "Активные заказы" (Real-time update 10 сек)
    @GetMapping("/active")
    fun getActiveOrders(): ResponseEntity<List<TaxiOrderDto>> {
        // Теперь orderService существует и ошибки не будет
        val orders = orderService.getActiveOrdersForDispatcher()
        return ResponseEntity.ok(orders)
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