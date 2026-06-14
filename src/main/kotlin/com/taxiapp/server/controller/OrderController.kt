package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.UserRepository // <-- ПЕРЕВІР ІМПОРТ
import com.taxiapp.server.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import com.taxiapp.server.dto.auth.MessageResponse
import org.springframework.http.ResponseEntity

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService,
    private val userRepository: UserRepository,
    private val taxiOrderRepository: com.taxiapp.server.repository.TaxiOrderRepository// <-- ДОДАНО: Щоб знайти користувача
) {

    private fun getInternalId(uuid: java.util.UUID): Long {
        return taxiOrderRepository.findByUuid(uuid)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
            .id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не має внутрішнього ID")
    }

    @PostMapping
    fun createOrder(
        @AuthenticationPrincipal userPrincipal: User?, // Тут може спрацювати, а може ні
        @RequestBody request: CreateOrderRequestDto,
        authentication: Authentication // Додаємо як резерв
    ): TaxiOrderDto {
        // Надійна логіка отримання користувача
        val user = userPrincipal ?: run {
            val phone = authentication.name
            userRepository.findByUserLogin(phone).orElse(null)
        }

        if (user == null || user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Только клиенты могут создавать заказы")
        }
        return orderService.createOrder(user, request)
    }

    @GetMapping("/history")
    fun getHistory(
        authentication: Authentication // <-- ВИКОРИСТОВУЄМО Authentication ЗАМІСТЬ @AuthenticationPrincipal
    ): List<TaxiOrderDto> {
        // 1. Беремо номер телефону з токена (це найнадійніше)
        val phone = authentication.name
        
        // 2. Завантажуємо свіжого користувача з бази
        val user = userRepository.findByUserLogin(phone).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено")

        // 3. Перевіряємо роль
        if (user !is Client) {
             return emptyList()
        }
        
        // 4. Отримуємо історію
        return orderService.getClientHistory(user)
    }

   @GetMapping("/{id}")
    fun getOrder(@PathVariable id: java.util.UUID): TaxiOrderDto { // <-- ТИП java.util.UUID
        val internalId = getInternalId(id)
        return orderService.getOrderById(internalId)
    }

    // Смена типа оплаты
    @PutMapping("/{id}/payment-method")
    fun updatePaymentMethod(
        @PathVariable("id") orderUuid: java.util.UUID, // <-- ТИП java.util.UUID
        @RequestParam("method") method: String
    ): ResponseEntity<MessageResponse> {
        val internalId = getInternalId(orderUuid)
        orderService.updatePaymentMethod(internalId, method)
        return ResponseEntity.ok(MessageResponse("Спосіб оплати успішно змінено"))
    }

    // Изменение цены (добавление надбавки)
    @PutMapping("/{id}/price")
    fun updateOrderPrice(
        @PathVariable("id") orderUuid: java.util.UUID, // <-- ТИП java.util.UUID
        @RequestParam("addedValue") addedValue: Double
    ): ResponseEntity<MessageResponse> {
        val internalId = getInternalId(orderUuid)
        orderService.updatePrice(internalId, addedValue)
        return ResponseEntity.ok(MessageResponse("Ціну успішно оновлено"))
    }

    @PostMapping("/{id}/cancel")
    fun cancelOrder(
        authentication: Authentication,
        @PathVariable id: java.util.UUID, // <-- ТИП java.util.UUID
        @RequestParam(required = false) reasonText: String? 
    ): TaxiOrderDto {
        val phone = authentication.name
        val user = userRepository.findByUserLogin(phone).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
            
        val internalId = getInternalId(id)
        return orderService.cancelOrder(user, internalId, reasonText)
    }
}