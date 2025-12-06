package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.CreateOrderRequestDto // <-- Исправлен импорт
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Client // <-- Импорт Client
import com.taxiapp.server.model.user.User
import com.taxiapp.server.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService
) {

    @PostMapping
    fun createOrder(
        @AuthenticationPrincipal user: User,
        @RequestBody request: CreateOrderRequestDto
    ): TaxiOrderDto {
        // ПРОВЕРКА И КАСТИНГ: Является ли пользователь Клиентом?
        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Только клиенты могут создавать заказы")
        }
        // Теперь 'user' автоматически считается 'Client' (Smart Cast)
        return orderService.createOrder(user, request)
    }

    @GetMapping("/{id}")
    fun getOrder(@PathVariable id: Long): TaxiOrderDto {
        return orderService.getOrderById(id)
    }

    @PostMapping("/{id}/cancel")
    fun cancelOrder(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long
    ): TaxiOrderDto {
        return orderService.cancelOrder(user, id)
    }
}