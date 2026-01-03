package com.taxiapp.server.service

import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.order.OrderStop
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.CarTariffRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiServiceRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class OrderService(
    private val orderRepository: TaxiOrderRepository, 
    private val tariffRepository: CarTariffRepository,
    private val promoService: PromoService,
    private val driverRepository: DriverRepository,
    private val promoCodeService: PromoCodeService,
    private val taxiServiceRepository: TaxiServiceRepository
) {

    @Transactional
    fun createOrder(client: Client, request: CreateOrderRequestDto): TaxiOrderDto {
        // 1. Проверка тарифа
        val tariff = tariffRepository.findById(request.tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }

        if (!tariff.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тариф недоступний")
        }

        // --- ЛОГИКА УСЛУГ ---
        val selectedServicesEntities = if (!request.serviceIds.isNullOrEmpty()) {
            taxiServiceRepository.findAllById(request.serviceIds)
        } else {
            emptyList()
        }

        // --- ЛОГИКА ЦЕНЫ ---
        var finalPrice = request.price 
        var discountAmount = 0.0
        var isPromoCodeUsedForThisOrder = false

        // 2. Логика Промокодов
        val activePromoUsage = promoCodeService.findActiveUsage(client)
        var promoCodeApplied = false

        if (activePromoUsage != null) {
            val isExpired = activePromoUsage.expiresAt != null && LocalDateTime.now().isAfter(activePromoUsage.expiresAt)
            if (!isExpired) {
                val percent = activePromoUsage.promoCode.discountPercent
                var calcDiscount = finalPrice * (percent / 100.0)
                val maxAmount = activePromoUsage.promoCode.maxDiscountAmount
                if (maxAmount != null && calcDiscount > maxAmount) {
                    calcDiscount = maxAmount
                }
                discountAmount = calcDiscount
                promoCodeApplied = true
                isPromoCodeUsedForThisOrder = true
            }
        }

        // 3. Логика Акций/Заданий
        if (!promoCodeApplied) {
            val activeReward = promoService.findActiveReward(client)
            if (activeReward != null) {
                val task = activeReward.promoTask
                val percent = task.discountPercent
                discountAmount = finalPrice * (percent / 100.0)
                if (task.maxDiscountAmount != null && discountAmount > task.maxDiscountAmount!!) {
                    discountAmount = task.maxDiscountAmount!!
                }
                isPromoCodeUsedForThisOrder = false
            }
        }

        // Итоговая цена после скидок
        finalPrice -= discountAmount
        if (finalPrice < 0) finalPrice = 0.0

        // 4. Создание объекта заказа
        val newOrder = TaxiOrder(
            client = client,
            fromAddress = request.fromAddress,
            toAddress = request.toAddress,
            status = OrderStatus.REQUESTED,
            createdAt = LocalDateTime.now(),
            tariff = tariff,
            price = finalPrice,
            
            appliedDiscount = discountAmount,
            isPromoCodeUsed = isPromoCodeUsedForThisOrder,
            
            originLat = request.originLat,
            originLng = request.originLng,
            destLat = request.destLat,
            destLng = request.destLng,
            googleRoutePolyline = request.googleRoutePolyline,
            distanceMeters = request.distanceMeters,
            durationSeconds = request.durationSeconds,
            
            tariffName = tariff.name,
            comment = request.comment,
            paymentMethod = request.paymentMethod ?: "CASH",
            addedValue = request.addedValue
        )

        // --- ПРИКРЕПЛЕНИЕ УСЛУГ К ЗАКАЗУ ---
        if (selectedServicesEntities.isNotEmpty()) {
            newOrder.selectedServices.addAll(selectedServicesEntities) 
        }

        // 5. Сохранение остановок (Waypoints)
        if (!request.waypoints.isNullOrEmpty()) {
            val stopsList = request.waypoints.mapIndexed { index, wpDto ->
                OrderStop(
                    address = wpDto.address,
                    lat = wpDto.lat,
                    lng = wpDto.lng,
                    stopOrder = index + 1,
                    order = newOrder
                )
            }
            newOrder.stops.addAll(stopsList)
        }

        val savedOrder = orderRepository.save(newOrder)
        return TaxiOrderDto(savedOrder)
    }

    fun getOrderById(id: Long): TaxiOrderDto {
        val order = orderRepository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        return TaxiOrderDto(order)
    }

    fun getClientHistory(client: Client): List<TaxiOrderDto> {
        val orders = orderRepository.findAllByClientId(client.id) 
        return orders.map { TaxiOrderDto(it) }
    }

    @Transactional
    fun cancelOrder(user: User, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.client.id != user.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Чуже замовлення")
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        order.status = OrderStatus.CANCELLED
        return TaxiOrderDto(orderRepository.save(order))
    }
    
    @Transactional
    fun acceptOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.status != OrderStatus.REQUESTED) throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже не активне")
        order.driver = driver
        order.status = OrderStatus.ACCEPTED
        return TaxiOrderDto(orderRepository.save(order))
    }

    // --- НОВИЙ МЕТОД: ВОДІЙ НА МІСЦІ ---
    @Transactional
    fun driverArrived(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        }
        // Можна перейти тільки якщо статус ACCEPTED (Їде до клієнта)
        if (order.status != OrderStatus.ACCEPTED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний статус замовлення")
        }

        order.status = OrderStatus.DRIVER_ARRIVED
        return TaxiOrderDto(orderRepository.save(order))
    }

    // --- НОВИЙ МЕТОД: ПОЧАТОК ПОЇЗДКИ ---
    @Transactional
    fun startTrip(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        }
        // Почати поїздку можна, якщо водій "На місці" або "Прийняв" (якщо забув натиснути на місці)
        if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.DRIVER_ARRIVED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не можна почати поїздку зараз")
        }

        order.status = OrderStatus.IN_PROGRESS
        return TaxiOrderDto(orderRepository.save(order))
    }

    @Transactional
    fun completeOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        
        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.IN_PROGRESS && order.status != OrderStatus.DRIVER_ARRIVED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення не в процесі")
        
        // Оновлюємо статус
        order.status = OrderStatus.COMPLETED
        order.completedAt = LocalDateTime.now()

        // Оновлюємо лічильник поїздок
        val currentRides = driver.completedRides
        driver.completedRides = currentRides + 1
        driverRepository.save(driver)
        
        if (order.appliedDiscount > 0.0) {
            if (order.isPromoCodeUsed) {
                val activePromoUsage = promoCodeService.findActiveUsage(order.client)
                if (activePromoUsage != null) {
                    promoCodeService.markAsUsed(activePromoUsage.id)
                }
            } else {
                promoService.markRewardAsUsed(order.client)
            }
        }
        
        promoService.updateProgressOnRideCompletion(order.client, order)
        return TaxiOrderDto(orderRepository.save(order))
    }

    fun toDto(order: TaxiOrder): TaxiOrderDto {
        return TaxiOrderDto(order)
    }

    fun findAllByStatus(status: OrderStatus): List<TaxiOrderDto> {
        return orderRepository.findAllByStatus(status)
            .map { TaxiOrderDto(it) } 
    }

    fun getActiveOrdersForDispatcher(): List<TaxiOrderDto> {
        val activeStatuses = listOf(
            OrderStatus.REQUESTED,      // Нові (Пошук)
            OrderStatus.ACCEPTED,       // Водій їде
            OrderStatus.DRIVER_ARRIVED, // Водій чекає (ТОЙ, ЩО ЗНИКАВ)
            OrderStatus.IN_PROGRESS     // В дорозі
        )
        
        // Використовуємо новий метод репозиторію
        return orderRepository.findAllByStatusIn(activeStatuses)
            .map { TaxiOrderDto(it) }
            .sortedByDescending { it.id } // Сортуємо: найновіші зверху
    }
}