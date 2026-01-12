package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.dto.order.OrderSocketMessage
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.order.OrderStop
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.model.filter.DriverFilter
import com.taxiapp.server.repository.*
import com.taxiapp.server.utils.GeometryUtils
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
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
    private val taxiServiceRepository: TaxiServiceRepository,
    private val filterRepository: DriverFilterRepository,
    private val sectorRepository: SectorRepository,
    private val messagingTemplate: SimpMessagingTemplate
) {

    // --- СТВОРЕННЯ ЗАМОВЛЕННЯ ---

    @Transactional
    fun createOrder(client: Client, request: CreateOrderRequestDto): TaxiOrderDto {
        // 1. Перевірка тарифа
        val tariff = tariffRepository.findById(request.tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }

        if (!tariff.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тариф недоступний")
        }

        // --- ЛОГІКА УСЛУГ ---
        val selectedServicesEntities = if (!request.serviceIds.isNullOrEmpty()) {
            taxiServiceRepository.findAllById(request.serviceIds)
        } else {
            emptyList()
        }

        // --- ЛОГІКА ЦІНИ ---
        var finalPrice = request.price 
        var discountAmount = 0.0
        var isPromoCodeUsedForThisOrder = false

        // 2. Логіка Промокодів
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

        // 3. Логіка Акцій/Задань
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

        // Підсумкова ціна після знижок
        finalPrice -= discountAmount
        if (finalPrice < 0) finalPrice = 0.0

        // 4. Створення об'єкта замовлення
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
            
            originLat = request.originLat ?: 0.0,
            originLng = request.originLng ?: 0.0,
            destLat = request.destLat ?: 0.0,
            destLng = request.destLng ?: 0.0,
            googleRoutePolyline = request.googleRoutePolyline,
            distanceMeters = request.distanceMeters ?: 0,
            durationSeconds = request.durationSeconds ?: 0,
            
            tariffName = tariff.name,
            comment = request.comment,
            paymentMethod = request.paymentMethod ?: "CASH",
            addedValue = request.addedValue ?: 0.0
        )

        // --- ПРИКРІПЛЕННЯ ПОСЛУГ ДО ЗАМОВЛЕННЯ ---
        if (selectedServicesEntities.isNotEmpty()) {
            newOrder.selectedServices.addAll(selectedServicesEntities) 
        }

        // 5. Збереження зупинок (Waypoints)
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

        // МИТТЄВА РОЗСИЛКА ВОДІЯМ (ADD)
        broadcastOrderChange(savedOrder, "ADD")

        return TaxiOrderDto(savedOrder)
    }

    // --- ЛОГІКА REAL-TIME ПОВІДОМЛЕНЬ (WEBSOCKET) ---

    /**
     * Розсилає зміни замовлення водіям.
     * action: "ADD" - нове замовлення (з фільтрацією)
     * action: "REMOVE" - видалити з екрана (без фільтрації)
     */
    fun broadcastOrderChange(order: TaxiOrder, action: String) {
        val orderDto = TaxiOrderDto(order)
        
        // Створюємо повідомлення (для ADD додаємо об'єкт, для REMOVE достатньо ID)
        val message = OrderSocketMessage(action, order.id!!, if (action == "ADD") orderDto else null)

        // 1. РОЗСИЛКА В ДИСПЕТЧЕРСЬКУ (Адмін бачить усе)
        // Ми додаємо цей рядок, щоб ActiveOrders.jsx отримував оновлення
        messagingTemplate.convertAndSend("/topic/admin/orders", message)

        // 2. РОЗСИЛКА ВОДІЯМ (Тільки онлайн)
        val onlineDrivers = driverRepository.findAll().filter { it.isOnline }

        for (driver in onlineDrivers) {
            // Якщо замовлення закривається/приймається (REMOVE) - шлемо всім, щоб прибрати з екрана
            if (action == "REMOVE") {
                val msgRemove = OrderSocketMessage(action, order.id!!, null)
                messagingTemplate.convertAndSend("/topic/drivers/${driver.id}/orders", msgRemove)
                continue
            }

            // Якщо нове замовлення (ADD) - перевіряємо фільтри водія
            val activeFilters = filterRepository.findAllByDriverId(driver.id!!)
                .filter { it.isActive }

            // Якщо фільтрів немає АБО замовлення підходить під фільтр -> відправляємо
            if (activeFilters.isEmpty() || activeFilters.any { matchesFilter(order, it, driver) }) {
                val msgAdd = OrderSocketMessage(action, order.id!!, orderDto)
                messagingTemplate.convertAndSend("/topic/drivers/${driver.id}/orders", msgAdd)
            }
        }
    }

    fun getDriverHeatmap(): List<HeatmapZoneDto> {
        val activeOrders = orderRepository.findAllByStatus(OrderStatus.REQUESTED)
        if (activeOrders.isEmpty()) return emptyList()

        // 1.25 км в градусах широты (приблизительно)
        val R = 0.0112 

        // Используем простую "шахматную" сетку (offset grid), 
        // чтобы центры кругов распределялись равномерно и плотно
        val VERT_STEP = 1.5 * R
        val HORIZ_BASE = Math.sqrt(3.0) * R

        val grouped = activeOrders.groupBy { order ->
            val lat = order.originLat!!
            val lng = order.originLng!!

            // Определяем ячейку сетки
            val row = Math.round(lat / VERT_STEP).toInt()
            val rowCenterLat = row * VERT_STEP
            val lngStep = HORIZ_BASE / Math.cos(Math.toRadians(rowCenterLat))
            val offset = if (row % 2 != 0) lngStep / 2.0 else 0.0
            val col = Math.round((lng - offset) / lngStep).toInt()

            Pair(row, col)
        }

        val zones = mutableListOf<HeatmapZoneDto>()

        for ((gridKey, ordersInCell) in grouped) {
            val (row, col) = gridKey
            val count = ordersInCell.size
            if (count < 1) continue

            val level = when {
                count >= 10 -> 3
                count >= 5 -> 2
                else -> 1
            }

            // Рассчитываем центр зоны
            val centerLat = row * VERT_STEP
            val lngStep = HORIZ_BASE / Math.cos(Math.toRadians(centerLat))
            val offset = if (row % 2 != 0) lngStep / 2.0 else 0.0
            val centerLng = (col * lngStep) + offset

            zones.add(HeatmapZoneDto(centerLat, centerLng, count, level))
        }
        return zones
    }
    // --- ГОЛОВНА ЛОГІКА ФІЛЬТРАЦІЇ ---

    fun getFilteredOrdersForDriver(driver: Driver): List<TaxiOrderDto> {
        val allOrders = orderRepository.findAllByStatus(OrderStatus.REQUESTED)
        val activeFilters = filterRepository.findAllByDriverId(driver.id!!)
            .filter { it.isActive }

        if (activeFilters.isEmpty()) {
            return allOrders.map { TaxiOrderDto(it) }
        }

        val filtered = allOrders.filter { order ->
            activeFilters.any { filter -> matchesFilter(order, filter, driver) }
        }

        return filtered.map { TaxiOrderDto(it) }
    }

    private fun matchesFilter(order: TaxiOrder, filter: DriverFilter, driver: Driver): Boolean {
        // 1. Блок ЗВІДКИ
        if (filter.fromType == "DISTANCE") {
            val distToOrder = GeometryUtils.calculateDistance(
                driver.currentLatitude ?: 0.0, driver.currentLongitude ?: 0.0,
                order.originLat ?: 0.0, order.originLng ?: 0.0
            )
            if (distToOrder > (filter.fromDistance ?: 30.0)) return false
        } else {
            if (filter.fromSectors.isNotEmpty()) {
                val startSectors = sectorRepository.findAllById(filter.fromSectors)
                val isInStartSector = startSectors.any { sector -> 
                    GeometryUtils.isPointInPolygon(order.originLat ?: 0.0, order.originLng ?: 0.0, sector.points)
                }
                if (!isInStartSector) return false
            }
        }

        // 2. Блок КУДИ (Сектори призначення)
        if (filter.toSectors.isNotEmpty()) {
            val endSectors = sectorRepository.findAllById(filter.toSectors)
            val isInEndSector = endSectors.any { sector ->
                GeometryUtils.isPointInPolygon(order.destLat ?: 0.0, order.destLng ?: 0.0, sector.points)
            }
            if (!isInEndSector) return false
        }

        // 3. Тип оплати
        if (filter.paymentType != "ANY" && filter.paymentType != null) {
            if (order.paymentMethod != filter.paymentType) return false
        }

        // 4. ТАРИФ
        val totalKm = (order.distanceMeters ?: 0) / 1000.0

        if (filter.tariffType == "SIMPLE") {
            if (order.price < (filter.minPrice ?: 0.0)) return false
            if (totalKm > 0) {
                val pricePerKm = order.price / totalKm
                if (pricePerKm < (filter.minPricePerKm ?: 0.0)) return false
            }
        } else {
            // СКЛАДНИЙ ТАРИФ (Логіка "Км у мінімалці")
            val minPrice = filter.complexMinPrice ?: 0.0
            val kmInMin = filter.complexKmInMin ?: 0.0
            val priceCity = filter.complexPriceKmCity ?: 0.0

            val requiredPrice = if (totalKm <= kmInMin) {
                minPrice
            } else {
                minPrice + (totalKm - kmInMin) * priceCity
            }

            if (order.price < requiredPrice) return false
        }

        return true
    }

    // --- КЕРУВАННЯ СТАТУСАМИ ТА ІСТОРІЯ ---

    fun findActiveOrderByDriver(driver: Driver): TaxiOrderDto? {
        val activeStatuses = listOf(OrderStatus.ACCEPTED, OrderStatus.DRIVER_ARRIVED, OrderStatus.IN_PROGRESS)
        return orderRepository.findAllByDriverId(driver.id!!)
            .filter { it.status in activeStatuses }
            .map { TaxiOrderDto(it) }
            .firstOrNull()
    }

    fun findHistoryByDriver(driver: Driver): List<TaxiOrderDto> {
        return orderRepository.findAllByDriverId(driver.id!!)
            .filter { it.status == OrderStatus.COMPLETED || it.status == OrderStatus.CANCELLED }
            .sortedByDescending { it.id }
            .map { TaxiOrderDto(it) }
    }

    fun getOrderById(id: Long): TaxiOrderDto {
        val order = orderRepository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        return TaxiOrderDto(order)
    }

    fun getClientHistory(client: Client): List<TaxiOrderDto> {
        return orderRepository.findAllByClientId(client.id!!).map { TaxiOrderDto(it) }
    }

    @Transactional
    fun cancelOrder(user: User, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.client.id != user.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Чуже замовлення")
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        
        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)
        
        // Повідомляємо водіїв про видалення
        broadcastOrderChange(saved, "REMOVE")
        
        return TaxiOrderDto(saved)
    }
    
    @Transactional
    fun acceptOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.status != OrderStatus.REQUESTED) throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже не активне")
        
        order.driver = driver
        order.status = OrderStatus.ACCEPTED
        val saved = orderRepository.save(order)
        
        // Видаляємо з ефіру в інших водіїв
        broadcastOrderChange(saved, "REMOVE")
        
        return TaxiOrderDto(saved)
    }

    @Transactional
    fun driverArrived(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний статус")

        order.status = OrderStatus.DRIVER_ARRIVED
        return TaxiOrderDto(orderRepository.save(order))
    }

    @Transactional
    fun startTrip(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.DRIVER_ARRIVED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не можна почати поїздку")
        }

        order.status = OrderStatus.IN_PROGRESS
        return TaxiOrderDto(orderRepository.save(order))
    }

    @Transactional
    fun completeOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        
        order.status = OrderStatus.COMPLETED
        order.completedAt = LocalDateTime.now()

        driver.completedRides += 1
        driverRepository.save(driver)
        
        if (order.appliedDiscount > 0.0) {
            if (order.isPromoCodeUsed) {
                val activePromoUsage = promoCodeService.findActiveUsage(order.client)
                activePromoUsage?.let { promoCodeService.markAsUsed(it.id) }
            } else {
                promoService.markRewardAsUsed(order.client)
            }
        }
        
        promoService.updateProgressOnRideCompletion(order.client, order)
        return TaxiOrderDto(orderRepository.save(order))
    }

    fun getActiveOrdersForDispatcher(): List<TaxiOrderDto> {
        val activeStatuses = listOf(OrderStatus.REQUESTED, OrderStatus.ACCEPTED, OrderStatus.DRIVER_ARRIVED, OrderStatus.IN_PROGRESS)
        return orderRepository.findAllByStatusIn(activeStatuses)
            .map { TaxiOrderDto(it) }
            .sortedByDescending { it.id }
    }

    fun mapToDto(order: TaxiOrder): TaxiOrderDto = TaxiOrderDto(order)
}