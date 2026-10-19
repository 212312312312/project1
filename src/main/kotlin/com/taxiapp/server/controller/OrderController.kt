package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService,
    private val userRepository: UserRepository,
    private val taxiOrderRepository: TaxiOrderRepository
) {

    private fun resolveUser(userPrincipal: User?, authentication: Authentication): User {
        userPrincipal?.let { return it }
        val loginOrPhone = authentication.name
        return userRepository.findByUserLogin(loginOrPhone)
            .orElseGet {
                userRepository.findByUserPhone(loginOrPhone)
                    .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
            }
    }

    private fun getInternalId(uuid: UUID): Long {
        return taxiOrderRepository.findByUuid(uuid)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
            .id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не має внутрішнього ID")
    }

    @PostMapping
    fun createOrder(
        @AuthenticationPrincipal userPrincipal: User?,
        @Valid @RequestBody request: CreateOrderRequestDto,
        authentication: Authentication
    ): TaxiOrderDto {
        val user = resolveUser(userPrincipal, authentication)
        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки клієнти можуть створювати замовлення")
        }
        return orderService.createOrder(user, request)
    }

    @GetMapping("/history")
    fun getHistory(
        @AuthenticationPrincipal userPrincipal: User?,
        authentication: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "30") size: Int
    ): List<TaxiOrderDto> {
        val user = resolveUser(userPrincipal, authentication)
        if (user !is Client) return emptyList()
        return orderService.getClientHistory(user, page, size.coerceIn(1, 100))
    }

    @GetMapping("/{id}")
    fun getOrder(
        @PathVariable id: UUID,
        @AuthenticationPrincipal userPrincipal: User?,
        authentication: Authentication
    ): TaxiOrderDto {
        val user = resolveUser(userPrincipal, authentication)
        val order = taxiOrderRepository.findByUuid(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        val isOwnerClient = order.client.id == user.id
        val isAssignedDriver = order.driver?.id == user.id || order.offeredDriver?.id == user.id
        val isAdminOrDispatcher = user.role == Role.ADMINISTRATOR || user.role == Role.DISPATCHER

        if (!isOwnerClient && !isAssignedDriver && !isAdminOrDispatcher) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого замовлення заборонено")
        }

        return TaxiOrderDto(order)
    }

    @PutMapping("/{id}/payment-method")
    fun updatePaymentMethod(
        @PathVariable("id") orderUuid: UUID,
        @RequestParam("method") method: String,
        @AuthenticationPrincipal userPrincipal: User?,
        authentication: Authentication
    ): ResponseEntity<MessageResponse> {
        val user = resolveUser(userPrincipal, authentication)
        val internalId = getInternalId(orderUuid)
        orderService.updatePaymentMethod(user, internalId, method)
        return ResponseEntity.ok(MessageResponse("Спосіб оплати успішно змінено"))
    }

    @PutMapping("/{id}/price")
    fun updateOrderPrice(
        @PathVariable("id") orderUuid: UUID,
        @RequestParam("addedValue") addedValue: Double,
        @AuthenticationPrincipal userPrincipal: User?,
        authentication: Authentication
    ): ResponseEntity<MessageResponse> {
        val user = resolveUser(userPrincipal, authentication)
        val internalId = getInternalId(orderUuid)
        orderService.updatePrice(user, internalId, addedValue)
        return ResponseEntity.ok(MessageResponse("Ціну успішно оновлено"))
    }

    @PostMapping("/{id}/cancel")
    fun cancelOrder(
        @AuthenticationPrincipal userPrincipal: User?,
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestParam(required = false) reasonText: String?
    ): TaxiOrderDto {
        val user = resolveUser(userPrincipal, authentication)
        val internalId = getInternalId(id)
        return orderService.cancelOrder(user, internalId, reasonText)
    }
}