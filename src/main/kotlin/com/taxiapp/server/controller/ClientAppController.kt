package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.dto.service.TaxiServiceDto
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.*
import com.taxiapp.server.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/api/v1/client")
class ClientAppController(
    private val orderService: OrderService,
    private val orderRepository: TaxiOrderRepository,
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val clientPromoProgressRepository: ClientPromoProgressRepository,
    private val smsCodeRepository: SmsVerificationCodeRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val taxiServiceRepository: TaxiServiceRepository,
    private val orderRatingRepository: OrderRatingRepository
) {

    // СТВОРЕННЯ ЗАМОВЛЕННЯ
    @PostMapping("/orders")
    fun createOrder(
        principal: Principal,
        @Valid @RequestBody request: CreateOrderRequestDto
    ): ResponseEntity<TaxiOrderDto> {
        val userLogin = principal.name

        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
            user = userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Користувач не є клієнтом")
        }

        if (user.isBlocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблоковано")
        }

        val orderDto = orderService.createOrder(user, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(orderDto)
    }

    // ОТРИМАННЯ ЗАМОВЛЕННЯ (З ЗАХИСТОМ ВІД IDOR)
    @GetMapping(*["/{id}", "/orders/{id}"])
    fun getOrder(@PathVariable id: String, principal: Principal): ResponseEntity<TaxiOrderDto> {
        val userLogin = principal.name
        val user = userRepository.findByUserLogin(userLogin)
            .orElseGet {
                userRepository.findByUserPhone(userLogin)
                    .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
            }

        val order = try {
            orderRepository.findByUuid(UUID.fromString(id))
                .orElseGet {
                    orderRepository.findById(id.toLongOrNull() ?: 0L).orElse(null)
                }
        } catch (e: IllegalArgumentException) {
            orderRepository.findById(id.toLongOrNull() ?: 0L).orElse(null)
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено")

        // ЗАЩИТА ОТ IDOR: Клиент может смотреть только СВОЙ заказ!
        if (order.client.id != user.id && user.role != Role.ADMINISTRATOR) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ви не маєте доступу до цього замовлення")
        }

        return ResponseEntity.ok(TaxiOrderDto(order))
    }

    @DeleteMapping("/account")
    @Transactional
    fun deleteAccount(principal: Principal): ResponseEntity<MessageResponse> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) user = userRepository.findByUserPhone(userLogin).orElseThrow()

        // 1. Удаляем промо
        val promos = clientPromoProgressRepository.findAllByClientId(user.id)
        clientPromoProgressRepository.deleteAll(promos)

        // 2. Находим системного клиента для архивных заказов
        val deletedUserDummy = clientRepository.findById(999999L).orElseThrow {
            RuntimeException("Системный аккаунт 'Deleted Account' не найден в БД! Выполни SQL скрипт.")
        }

        // 3. Перевешиваем заказы на заглушку
        val orders = orderRepository.findAllByClientId(user.id)
        orders.forEach { order ->
            order.client = deletedUserDummy
        }
        orderRepository.saveAll(orders)

        // 4. Удаляем SMS
        user.userPhone?.let { phone ->
            smsCodeRepository.findByUserPhone(phone).ifPresent {
                smsCodeRepository.delete(it)
            }
        }

        // 5. Удаляем токены
        refreshTokenRepository.deleteByUser(user)

        // 6. Удаляем оценки (рейтинг)
        val ratings = orderRatingRepository.findAllByTargetUserId(user.id)
        orderRatingRepository.deleteAll(ratings)

        // 7. Удаляем самого пользователя
        userRepository.delete(user)

        return ResponseEntity.ok(MessageResponse("Акаунт успішно видалено"))
    }

    // СКАСУВАННЯ ЗАМОВЛЕННЯ
    @PostMapping("/orders/{id}/cancel")
    fun cancelOrder(
        @PathVariable id: String,
        @RequestParam(required = false) reasonText: String?,
        principal: Principal
    ): ResponseEntity<TaxiOrderDto> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) user = userRepository.findByUserPhone(userLogin).orElseThrow()

        val order = try {
            orderRepository.findByUuid(UUID.fromString(id))
                .orElseGet {
                    orderRepository.findById(id.toLongOrNull() ?: 0L).orElse(null)
                }
        } catch (e: IllegalArgumentException) {
            orderRepository.findById(id.toLongOrNull() ?: 0L).orElse(null)
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено")

        if (order.client.id != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ви не маєте доступу до цього замовлення")
        }

        val orderDto = orderService.cancelOrder(user, order.id!!, reasonText)
        return ResponseEntity.ok(orderDto)
    }

    // ИСТОРИЯ ЗАКАЗОВ
    @GetMapping("/orders")
    fun getClientOrders(authentication: Authentication): ResponseEntity<List<TaxiOrderDto>> {
        val userDetails = authentication.principal as UserDetails

        val client = clientRepository.findByUserPhone(userDetails.username)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Клієнта не знайдено") }

        val dtos = orderService.getClientHistory(client)

        return ResponseEntity.ok(dtos)
    }

    @GetMapping("/services")
    fun getActiveServices(): ResponseEntity<List<TaxiServiceDto>> {
        val services = taxiServiceRepository.findAllByIsActiveTrue().map {
            TaxiServiceDto(it.id!!, it.name, it.price)
        }
        return ResponseEntity.ok(services)
    }

    @GetMapping("/profile")
    fun getClientProfile(principal: Principal): ResponseEntity<com.taxiapp.server.dto.client.ClientDto> {
        val userLogin = principal.name

        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
            user = userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено") }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Користувач не є клієнтом")
        }

        return ResponseEntity.ok(com.taxiapp.server.dto.client.ClientDto(user))
    }

    @PutMapping("/profile")
    @Transactional
    fun updateClientProfile(
        principal: Principal,
        @Valid @RequestBody request: com.taxiapp.server.dto.client.UpdateClientProfileRequest
    ): ResponseEntity<com.taxiapp.server.dto.client.ClientDto> {
        val cleanName = request.fullName.trim()
        if (cleanName.length < 2 || cleanName.length > 50) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ім'я повинно містити від 2 до 50 символів")
        }

        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
            user = userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено") }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Користувач не є клієнтом")
        }

        user.fullName = cleanName
        val savedClient = clientRepository.save(user)
        return ResponseEntity.ok(com.taxiapp.server.dto.client.ClientDto(savedClient))
    }

    // ОДЕРЖАННЯ АКТИВНОГО АБО ЩОЙНО ЗАВЕРШЕНОГО ЗАМОВЛЕННЯ
    @GetMapping("/orders/active")
    fun getActiveOrder(principal: Principal): ResponseEntity<TaxiOrderDto> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
            user = userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }
        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Користувач не є клієнтом")
        }

        val activeOrder = orderService.findActiveOrRecentlyCompletedOrder(user)
            ?: return ResponseEntity.noContent().build()

        return ResponseEntity.ok(activeOrder)
    }

    // ЗАКРИТТЯ КАРТКИ ЗАВЕРШЕНОГО ЗАМОВЛЕННЯ
    @PostMapping("/orders/{id}/dismiss")
    fun dismissOrder(
        @PathVariable id: String,
        principal: Principal
    ): ResponseEntity<MessageResponse> {
        val userLogin = principal.name
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
            user = userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }
        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Користувач не є клієнтом")
        }

        val internalId = try {
            orderRepository.findByUuid(UUID.fromString(id)).map { it.id }.orElse(null)
                ?: id.toLongOrNull()
        } catch (e: IllegalArgumentException) {
            id.toLongOrNull()
        } ?: 0L

        orderService.dismissCompletedOrder(user, internalId)
        return ResponseEntity.ok(MessageResponse("Замовлення успішно закрито"))
    }
}