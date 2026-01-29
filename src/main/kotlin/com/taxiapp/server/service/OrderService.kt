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
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import com.taxiapp.server.dto.order.CalculatedTariffDto
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import com.taxiapp.server.dto.tariff.CarTariffDto

@Service
class OrderService(
    private val notificationService: NotificationService,
    private val orderRepository: TaxiOrderRepository,
    private val tariffRepository: CarTariffRepository,
    private val promoService: PromoService,
    private val driverRepository: DriverRepository,
    private val promoCodeService: PromoCodeService,
    private val taxiServiceRepository: TaxiServiceRepository,
    private val filterRepository: DriverFilterRepository,
    private val sectorRepository: SectorRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val driverActivityService: DriverActivityService,
    private val sectorService: SectorService
) {

    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    private fun calculateExactTripPrice(
        tariffId: Long,
        polyline: String?,
        totalDistanceMeters: Int,
        serviceIds: List<Long>?,
        addedValue: Double,
        isDebug: Boolean = false
    ): Double {
        val tariff = tariffRepository.findById(tariffId)
            .orElseThrow { RuntimeException("Tariff not found") }

        var servicesCost = 0.0
        if (!serviceIds.isNullOrEmpty()) {
            val services = taxiServiceRepository.findAllById(serviceIds)
            servicesCost = services.sumOf { it.price }
        }

        if (polyline.isNullOrEmpty() || totalDistanceMeters == 0) {
            return tariff.basePrice + servicesCost + addedValue
        }

        val citySectors = sectorRepository.findAll().filter { it.isCity }
        val (metersCity, metersOutCity) = GeometryUtils.calculateRouteSplit(polyline, citySectors)

        val finalMetersCity = if (metersCity == 0.0 && metersOutCity == 0.0) totalDistanceMeters.toDouble() else metersCity
        val finalMetersOutCity = if (metersCity == 0.0 && metersOutCity == 0.0) 0.0 else metersOutCity

        val totalKmCity = finalMetersCity / 1000.0
        val totalKmOutCity = finalMetersOutCity / 1000.0

        val INCLUDED_KM = 3.0
        var remainingIncluded = INCLUDED_KM

        var billableKmCity = 0.0
        if (totalKmCity > remainingIncluded) {
            billableKmCity = totalKmCity - remainingIncluded
            remainingIncluded = 0.0
        } else {
            remainingIncluded -= totalKmCity
            billableKmCity = 0.0
        }

        var billableKmOutCity = 0.0
        if (totalKmOutCity > 0) {
            if (remainingIncluded > 0) {
                if (totalKmOutCity > remainingIncluded) {
                    billableKmOutCity = totalKmOutCity - remainingIncluded
                    remainingIncluded = 0.0
                } else {
                    billableKmOutCity = 0.0
                }
            } else {
                billableKmOutCity = totalKmOutCity
            }
        }

        val routePrice = (billableKmCity * tariff.pricePerKm) +
                         (billableKmOutCity * tariff.pricePerKmOutCity)

        var finalPrice = tariff.basePrice + routePrice + servicesCost + addedValue
        finalPrice = ceil(finalPrice)
        val result = max(finalPrice, tariff.basePrice)
        return result
    }

    fun calculatePricesForRoute(polyline: String, totalMeters: Int): List<CarTariffDto> {
        val tariffs = tariffRepository.findAll().filter { it.isActive }
        return tariffs.map { tariff ->
            val price = calculateExactTripPrice(tariff.id, polyline, totalMeters, emptyList(), 0.0, false)
            CarTariffDto(
                id = tariff.id,
                name = tariff.name,
                basePrice = tariff.basePrice,
                pricePerKm = tariff.pricePerKm,
                pricePerKmOutCity = tariff.pricePerKmOutCity,
                freeWaitingMinutes = tariff.freeWaitingMinutes,
                pricePerWaitingMinute = tariff.pricePerWaitingMinute,
                isActive = tariff.isActive,
                imageUrl = tariff.imageUrl,
                calculatedPrice = price,
                description = null
            )
        }
    }

    @Transactional
    fun createOrder(client: Client, request: CreateOrderRequestDto): TaxiOrderDto {
        logger.info(">>> СОЗДАНИЕ ЗАКАЗА: From='${request.fromAddress}'")
        val tariff = tariffRepository.findById(request.tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }

        if (!tariff.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тариф недоступний")
        }

        if (request.scheduledAt != null) {
            val now = LocalDateTime.now()
            if (request.scheduledAt.isBefore(now)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Час подачі не може бути в минулому")
            }
            if (request.scheduledAt.isAfter(now.plusDays(7))) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не можна бронювати більше ніж на 7 днів наперед")
            }
        }

        var finalPrice = calculateExactTripPrice(
            tariffId = request.tariffId,
            polyline = request.googleRoutePolyline,
            totalDistanceMeters = request.distanceMeters ?: 0,
            serviceIds = request.serviceIds,
            addedValue = request.addedValue ?: 0.0,
            isDebug = true
        )

        var discountAmount = 0.0
        var isPromoCodeUsedForThisOrder = false
        var promoCodeApplied = false

        val activePromoUsage = promoCodeService.findActiveUsage(client)
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

        finalPrice -= discountAmount
        if (finalPrice < tariff.basePrice) finalPrice = tariff.basePrice

        val allSectors = sectorRepository.findAll()
        val destSector = if (request.destLat != null && request.destLng != null) {
            allSectors.find { sector ->
                val points = sector.points.sortedBy { it.pointOrder }
                GeometryUtils.isPointInPolygon(request.destLat, request.destLng, points)
            }
        } else null

        val originSector = if (request.originLat != null && request.originLng != null) {
            allSectors.find { sector ->
                val points = sector.points.sortedBy { it.pointOrder }
                GeometryUtils.isPointInPolygon(request.originLat, request.originLng, points)
            }
        } else null

        val initialStatus = if (request.scheduledAt != null) OrderStatus.SCHEDULED else OrderStatus.REQUESTED

        val newOrder = TaxiOrder(
            client = client,
            fromAddress = request.fromAddress,
            toAddress = request.toAddress,
            status = initialStatus, 
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
            addedValue = request.addedValue ?: 0.0,
            destinationSector = destSector,
            originSector = originSector,
            scheduledAt = request.scheduledAt
        )

        if (!request.serviceIds.isNullOrEmpty()) {
            val services = taxiServiceRepository.findAllById(request.serviceIds)
            newOrder.selectedServices.addAll(services)
        }

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

        // --- ВАЖНО: Если запланированный - сохраняем и уведомляем диспетчера ---
        if (initialStatus == OrderStatus.SCHEDULED) {
            logger.info("Order scheduled for ${newOrder.scheduledAt}")
            val savedScheduled = orderRepository.save(newOrder)
            // Диспетчер получит это сообщение благодаря обновленному broadcastOrderChange
            broadcastOrderChange(savedScheduled, "ADD") 
            return TaxiOrderDto(savedScheduled)
        }

        val rejectedIds = if (newOrder.rejectedDriverIds.isNotEmpty()) {
            newOrder.rejectedDriverIds.toList()
        } else null

        val bestDriver = driverRepository.findBestDriverForOrder(
            newOrder.originLat!!,
            newOrder.originLng!!,
            destSector?.id,
            rejectedIds
        ).orElse(null)

        if (bestDriver != null) {
            newOrder.status = OrderStatus.OFFERING 
            newOrder.offeredDriver = bestDriver
            newOrder.offerExpiresAt = LocalDateTime.now().plusSeconds(20) 
            notificationService.sendOrderOffer(bestDriver, newOrder)
        } else {
            newOrder.status = OrderStatus.REQUESTED
        }

        val savedOrder = orderRepository.save(newOrder)

        if (savedOrder.status == OrderStatus.REQUESTED) {
            broadcastOrderChange(savedOrder, "ADD")
        }

        return TaxiOrderDto(savedOrder)
    }

    @Transactional
    fun rejectOffer(driver: Driver, orderId: Long) {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        
        if (order.status == OrderStatus.OFFERING && order.offeredDriver?.id == driver.id) {
            logger.info("Водій ${driver.id} відхилив замовлення ${order.id}")
            order.rejectedDriverIds.add(driver.id!!)
            order.status = OrderStatus.REQUESTED
            order.offeredDriver = null
            order.offerExpiresAt = null
            
            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD") 
        }
    }

    @Scheduled(fixedRate = 1000)
    @Transactional
    fun checkExpiredOffers() {
        val now = LocalDateTime.now()
        val expiredOrders = orderRepository.findAllByStatus(OrderStatus.OFFERING)
            .filter { it.offerExpiresAt != null && it.offerExpiresAt!!.isBefore(now) }

        for (order in expiredOrders) {
            logger.info("Час пропозиції замовлення ${order.id} вичерпано. Переведення в Ефір.")
            order.offeredDriver?.let { order.rejectedDriverIds.add(it.id!!) }
            order.status = OrderStatus.REQUESTED
            order.offeredDriver = null
            order.offerExpiresAt = null
            
            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD")
        }
    }
    
    // =================================================================================
    // 🧠 ИСПРАВЛЕННЫЙ broadcastOrderChange
    // =================================================================================
    fun broadcastOrderChange(order: TaxiOrder, action: String) {
        val orderDto = TaxiOrderDto(order)
        val message = OrderSocketMessage(action, order.id!!, if (action == "ADD") orderDto else null)

        // 1. Отправляем диспетчеру ВСЕГДА (включая SCHEDULED)
        messagingTemplate.convertAndSend("/topic/admin/orders", message)

        // 2. Если это SCHEDULED, то водителям пока не отправляем
        if (order.status == OrderStatus.SCHEDULED) return

        val onlineDrivers = driverRepository.findAll().filter { it.isOnline }

        for (driver in onlineDrivers) {
            if (driver.activityScore <= 0) continue

            if (action == "REMOVE") {
                val msgRemove = OrderSocketMessage(action, order.id!!, null)
                messagingTemplate.convertAndSend("/topic/drivers/${driver.id}/orders", msgRemove)
                continue
            }

            val activeFilters = filterRepository.findAllByDriverId(driver.id!!)
                .filter { it.isActive }

            if (activeFilters.isEmpty() || activeFilters.any { matchesFilter(order, it, driver) }) {
                val msgAdd = OrderSocketMessage(action, order.id!!, orderDto)
                messagingTemplate.convertAndSend("/topic/drivers/${driver.id}/orders", msgAdd)
            }
        }
    }

    fun getDriverHeatmap(): List<HeatmapZoneDto> {
        val activeOrders = orderRepository.findAllByStatus(OrderStatus.REQUESTED)
        if (activeOrders.isEmpty()) return emptyList()

        val R = 0.0112 
        val VERT_STEP = 1.5 * R
        val HORIZ_BASE = Math.sqrt(3.0) * R

        val grouped = activeOrders.groupBy { order ->
            val lat = order.originLat!!
            val lng = order.originLng!!
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

            val centerLat = row * VERT_STEP
            val lngStep = HORIZ_BASE / Math.cos(Math.toRadians(centerLat))
            val offset = if (row % 2 != 0) lngStep / 2.0 else 0.0
            val centerLng = (col * lngStep) + offset

            zones.add(HeatmapZoneDto(centerLat, centerLng, count, level))
        }
        return zones
    }

    fun getFilteredOrdersForDriver(driver: Driver): List<TaxiOrderDto> {
        if (driver.activityScore <= 0) return emptyList()

        val statuses = listOf(OrderStatus.REQUESTED, OrderStatus.SCHEDULED)
        val allOrders = orderRepository.findAllByStatusIn(statuses)
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
        if (filter.fromType == "DISTANCE") {
            val distToOrder = GeometryUtils.calculateDistance(
                driver.latitude ?: 0.0, driver.longitude ?: 0.0,
                order.originLat ?: 0.0, order.originLng ?: 0.0
            )
            if (distToOrder > (filter.fromDistance ?: 30.0)) return false
        } else {
            if (filter.fromSectors.isNotEmpty()) {
                val startSectors = sectorRepository.findAllById(filter.fromSectors)
                val isInStartSector = startSectors.any { sector -> 
                    val sorted = sector.points.sortedBy { it.pointOrder }
                    GeometryUtils.isPointInPolygon(order.originLat ?: 0.0, order.originLng ?: 0.0, sorted)
                }
                if (!isInStartSector) return false
            }
        }

        if (filter.toSectors.isNotEmpty()) {
            val endSectors = sectorRepository.findAllById(filter.toSectors)
            val isInEndSector = endSectors.any { sector ->
                val sorted = sector.points.sortedBy { it.pointOrder }
                GeometryUtils.isPointInPolygon(order.destLat ?: 0.0, order.destLng ?: 0.0, sorted)
            }
            if (!isInEndSector) return false
        }

        if (filter.paymentType != "ANY" && filter.paymentType != null) {
            if (order.paymentMethod != filter.paymentType) return false
        }

        val totalKm = (order.distanceMeters ?: 0) / 1000.0
        if (filter.tariffType == "SIMPLE") {
            if (order.price < (filter.minPrice ?: 0.0)) return false
            if (totalKm > 0) {
                val pricePerKm = order.price / totalKm
                if (pricePerKm < (filter.minPricePerKm ?: 0.0)) return false
            }
        } else {
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

    fun findActiveOrderByDriver(driver: Driver): TaxiOrderDto? {
        val activeStatuses = listOf(
            OrderStatus.ACCEPTED,
            OrderStatus.DRIVER_ARRIVED,
            OrderStatus.IN_PROGRESS,
            OrderStatus.SCHEDULED // <--- ВАЖНО: Добавили это!
        )
        // Ищем заказ, где этот водитель назначен главным (driver_id)
        val activeOrder = orderRepository.findAllByDriverId(driver.id!!)
            .filter { it.status in activeStatuses }
            .firstOrNull()

        if (activeOrder != null) {
            return TaxiOrderDto(activeOrder)
        }

        val offeredOrder = orderRepository.findAllByStatus(OrderStatus.OFFERING)
            .find { it.offeredDriver?.id == driver.id }

        return if (offeredOrder != null) {
            if (offeredOrder.offerExpiresAt != null && LocalDateTime.now().isAfter(offeredOrder.offerExpiresAt)) {
                null 
            } else {
                TaxiOrderDto(offeredOrder)
            }
        } else {
            null
        }
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
        broadcastOrderChange(saved, "REMOVE")
        return TaxiOrderDto(saved)
    }

    @Transactional
    fun driverCancelOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        }

        driverActivityService.processOrderCancellation(driver, orderId)
        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)

        broadcastOrderChange(saved, "REMOVE")
        return TaxiOrderDto(saved)
    }
    
    @Transactional
    fun acceptOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        if (driver.activityScore <= 0) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Низька активність.")

        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        
        // Логика для обычных предложений (OFFERING)
        if (order.status == OrderStatus.OFFERING) {
             // ... (старая логика проверки водителя и времени) ...
             if (order.offeredDriver?.id != driver.id) throw ResponseStatusException(HttpStatus.CONFLICT, "Зайнято")
             // ...
        } 
        // Логика для запланированных (SCHEDULED)
        else if (order.status == OrderStatus.SCHEDULED) {
            // Если у заказа уже есть водитель
            if (order.driver != null) throw ResponseStatusException(HttpStatus.CONFLICT, "Вже має водія")
        }
        else if (order.status != OrderStatus.REQUESTED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже зайняте")
        }
        
        // НАЗНАЧАЕМ ВОДИТЕЛЯ
        order.driver = driver
        order.offeredDriver = null
        
        // ГЛАВНОЕ ИЗМЕНЕНИЕ:
        // Если это запланированный заказ -> статус НЕ меняем (остается SCHEDULED)
        // Если это обычный заказ -> ставим ACCEPTED
        if (order.status != OrderStatus.SCHEDULED) {
            order.status = OrderStatus.ACCEPTED
        } else {
            logger.info("Driver ${driver.id} reserved scheduled order ${order.id}")
        }
        
        // Списываем поездки "Домой" (если надо) ...
        
        val saved = orderRepository.save(order)
        broadcastOrderChange(saved, "ADD") // Обновляем диспетчера и водителя
        return TaxiOrderDto(saved)
    }

    @Transactional
    fun driverArrived(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний статус")

        order.status = OrderStatus.DRIVER_ARRIVED
        order.arrivedAt = LocalDateTime.now() 
        
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
        driverActivityService.processOrderCompletion(driver, order)
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
    
    // --- ПЛАНУВАЛЬНИК: Активація запланованих поїздок ---
    @Scheduled(fixedRate = 60000)
    @Transactional
    fun activateScheduledOrders() {
        val now = LocalDateTime.now()
        val activationThreshold = now.plusMinutes(30)

        val pendingOrders = orderRepository.findAllByStatusAndScheduledAtBefore(OrderStatus.SCHEDULED, activationThreshold)

        for (order in pendingOrders) {
            // Перевірка на прострочення (більше години тому)
            if (order.scheduledAt != null && order.scheduledAt!!.isBefore(now.minusHours(1))) {
                logger.warn("Scheduled order ${order.id} expired. Cancelling.")
                order.status = OrderStatus.CANCELLED
                orderRepository.save(order)
                continue
            }

            logger.info("ACTIVATING SCHEDULED ORDER #${order.id}")

            // ВАРИАНТ А: Водитель уже принял заказ заранее
            if (order.driver != null) {
                logger.info("Order #${order.id} has driver. Switching to ACCEPTED (Start mission).")
                order.status = OrderStatus.ACCEPTED
                // Відправляємо пуш водію
                notificationService.sendOrderOffer(order.driver!!, order)
            }
            // ВАРИАНТ Б: Водителя нет, начинаем поиск
            else {
                order.status = OrderStatus.REQUESTED

                // --- ВИПРАВЛЕНО ТУТ: Додані реальні аргументи замість "..." ---
                val bestDriver = driverRepository.findBestDriverForOrder(
                    order.originLat ?: 0.0,
                    order.originLng ?: 0.0,
                    order.destinationSector?.id,
                    null // rejectedIds = null, бо це новий пошук
                ).orElse(null)

                if (bestDriver != null) {
                    order.status = OrderStatus.OFFERING
                    order.offeredDriver = bestDriver
                    order.offerExpiresAt = LocalDateTime.now().plusSeconds(20)
                    
                    notificationService.sendOrderOffer(bestDriver, order)
                    logger.info("Order #${order.id} offered to driver ${bestDriver.id}")
                }
            }

            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD")
        }
    }


    fun getActiveOrdersForDispatcher(): List<TaxiOrderDto> {
        val activeStatuses = listOf(
            OrderStatus.REQUESTED, 
            OrderStatus.OFFERING, 
            OrderStatus.ACCEPTED, 
            OrderStatus.DRIVER_ARRIVED, 
            OrderStatus.IN_PROGRESS,
            OrderStatus.SCHEDULED 
        )
        return orderRepository.findAllByStatusIn(activeStatuses)
            .map { TaxiOrderDto(it) }
            .sortedByDescending { it.id }
    }

    fun mapToDto(order: TaxiOrder): TaxiOrderDto = TaxiOrderDto(order)
}