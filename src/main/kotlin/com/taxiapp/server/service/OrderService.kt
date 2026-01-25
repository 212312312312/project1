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

    // =================================================================================
    // 🧠 ЄДИНА ЛОГІКА РОЗРАХУНКУ ЦІНИ (Single Source of Truth)
    // =================================================================================
    private fun calculateExactTripPrice(
        tariffId: Long,
        polyline: String?,
        totalDistanceMeters: Int,
        serviceIds: List<Long>?,
        addedValue: Double,
        isDebug: Boolean = false // Прапорець для логування при створенні замовлення
    ): Double {
        val tariff = tariffRepository.findById(tariffId)
            .orElseThrow { RuntimeException("Tariff not found") }

        // 1. Рахуємо вартість послуг
        var servicesCost = 0.0
        if (!serviceIds.isNullOrEmpty()) {
            val services = taxiServiceRepository.findAllById(serviceIds)
            servicesCost = services.sumOf { it.price }
        }

        // 2. Якщо маршруту немає, повертаємо Базу + Послуги + Чайові
        if (polyline.isNullOrEmpty() || totalDistanceMeters == 0) {
            if (isDebug) logger.warn("⚠️ PRICE DEBUG: No Route. Returning Base: ${tariff.basePrice}")
            return tariff.basePrice + servicesCost + addedValue
        }

        // 3. Завантажуємо сектори МІСТА
        val citySectors = sectorRepository.findAll().filter { it.isCity }

        // 4. Розбиваємо маршрут
        val (metersCity, metersOutCity) = GeometryUtils.calculateRouteSplit(polyline, citySectors)

        // Фолбек
        val finalMetersCity = if (metersCity == 0.0 && metersOutCity == 0.0) totalDistanceMeters.toDouble() else metersCity
        val finalMetersOutCity = if (metersCity == 0.0 && metersOutCity == 0.0) 0.0 else metersOutCity

        val totalKmCity = finalMetersCity / 1000.0
        val totalKmOutCity = finalMetersOutCity / 1000.0

        // 5. ЛОГІКА 3 КМ (МІНІМАЛКИ)
        val INCLUDED_KM = 3.0
        var remainingIncluded = INCLUDED_KM

        // А. Списуємо з міста
        var billableKmCity = 0.0
        if (totalKmCity > remainingIncluded) {
            billableKmCity = totalKmCity - remainingIncluded
            remainingIncluded = 0.0
        } else {
            remainingIncluded -= totalKmCity
            billableKmCity = 0.0
        }

        // Б. Списуємо із заміста
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

        // 6. Рахуємо ціну за маршрут
        val routePrice = (billableKmCity * tariff.pricePerKm) +
                         (billableKmOutCity * tariff.pricePerKmOutCity)

        // 7. Підсумок
        var finalPrice = tariff.basePrice + routePrice + servicesCost + addedValue
        finalPrice = ceil(finalPrice)
        val result = max(finalPrice, tariff.basePrice)

        // --- ДЕТАЛЬНИЙ ЛОГ ДЛЯ ПОШУКУ ПОМИЛКИ ---
        if (isDebug) {
            logger.info("================ PRICE CALCULATION DEBUG ================")
            logger.info("Tariff Base:     ${tariff.basePrice} ₴")
            logger.info("Distance:        ${totalDistanceMeters}m (City: ${"%.2f".format(totalKmCity)}km, Out: ${"%.2f".format(totalKmOutCity)}km)")
            logger.info("Billable Km:     City: ${"%.2f".format(billableKmCity)}km, Out: ${"%.2f".format(billableKmOutCity)}km")
            logger.info("Route Cost:      ${"%.2f".format(routePrice)} ₴")
            logger.info("Services Cost:   $servicesCost ₴")
            logger.info("Added Value (Tip): $addedValue ₴  <--- ПЕРЕВІР ЦЕ ЗНАЧЕННЯ!")
            logger.info("---------------------------------------------------------")
            logger.info("FINAL CALC: ${tariff.basePrice} + $routePrice + $servicesCost + $addedValue = $finalPrice")
            logger.info("RESULT: $result ₴")
            logger.info("=========================================================")
        }

        return result
    }

    // =================================================================================
    // 1. МЕТОД ДЛЯ КЛІЄНТА (ПЕРЕГЛЯД ЦІН)
    // =================================================================================
    fun calculatePricesForRoute(polyline: String, totalMeters: Int): List<CarTariffDto> {
        val tariffs = tariffRepository.findAll().filter { it.isActive }

        return tariffs.map { tariff ->
            // 1. Рахуємо ціну
            val price = calculateExactTripPrice(
                tariffId = tariff.id,
                polyline = polyline,
                totalDistanceMeters = totalMeters,
                serviceIds = emptyList(),
                addedValue = 0.0,
                isDebug = false
            )

            // 2. Створюємо DTO
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
                calculatedPrice = price, // Передаємо пораховану ціну
                description = null       // Передаємо null, бо в базі (Entity) немає опису
            )
        }
    }

    // =================================================================================
    // 2. СТВОРЕННЯ ЗАМОВЛЕННЯ
    // =================================================================================
    @Transactional
    fun createOrder(client: Client, request: CreateOrderRequestDto): TaxiOrderDto {
        logger.info(">>> СОЗДАНИЕ ЗАКАЗА: From='${request.fromAddress}' Coords=[${request.originLat}, ${request.originLng}]")
        val tariff = tariffRepository.findById(request.tariffId)
        
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }

        if (!tariff.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тариф недоступний")
        }

        // РАХУЄМО ЦІНУ З ЛОГУВАННЯМ (isDebug = true)
        var finalPrice = calculateExactTripPrice(
            tariffId = request.tariffId,
            polyline = request.googleRoutePolyline,
            totalDistanceMeters = request.distanceMeters ?: 0,
            serviceIds = request.serviceIds,
            addedValue = request.addedValue ?: 0.0,
            isDebug = true
        )

        // --- ЛОГІКА ЗНИЖОК ---
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

        // --- НОВА НАДІЙНА ЛОГІКА ПОШУКУ СЕКТОРІВ ---
        // Завантажуємо всі сектори (щоб не робити N запитів в БД)
        val allSectors = sectorRepository.findAll()

        // 1. Шукаємо Сектор Призначення (Куди)
        val destSector = if (request.destLat != null && request.destLng != null) {
            allSectors.find { sector ->
                val points = sector.points.sortedBy { it.pointOrder }
                GeometryUtils.isPointInPolygon(request.destLat, request.destLng, points)
            }
        } else null

        // 2. Шукаємо Сектор Подачі (Звідки)
        val originSector = if (request.originLat != null && request.originLng != null) {
            allSectors.find { sector ->
                val points = sector.points.sortedBy { it.pointOrder }
                GeometryUtils.isPointInPolygon(request.originLat, request.originLng, points)
            }
        } else null
        // ---------------------------------------------

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
            addedValue = request.addedValue ?: 0.0,
            destinationSector = destSector,
            originSector = originSector // <-- Тепер тут буде правильний сектор
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
    
    fun broadcastOrderChange(order: TaxiOrder, action: String) {
        val orderDto = TaxiOrderDto(order)
        val message = OrderSocketMessage(action, order.id!!, if (action == "ADD") orderDto else null)

        messagingTemplate.convertAndSend("/topic/admin/orders", message)

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
            OrderStatus.IN_PROGRESS
        )
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

        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        
        if (order.status == OrderStatus.OFFERING) {
            if (order.offeredDriver?.id != driver.id) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Це замовлення пропонується іншому водію")
            }
            if (order.offerExpiresAt != null && LocalDateTime.now().isAfter(order.offerExpiresAt)) {
                 order.status = OrderStatus.REQUESTED
                 order.offeredDriver = null
                 orderRepository.save(order)
                 broadcastOrderChange(order, "ADD")
                 throw ResponseStatusException(HttpStatus.CONFLICT, "Час пропозиції вичерпано")
            }
        } else if (order.status != OrderStatus.REQUESTED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже зайняте")
        }
        
        order.driver = driver
        order.status = OrderStatus.ACCEPTED
        order.offeredDriver = null
        
        if (driver.searchMode == com.taxiapp.server.model.enums.DriverSearchMode.HOME && order.destinationSector != null) {
             val isHomeOrder = driver.homeSectors.any { it.id == order.destinationSector!!.id }
             if (isHomeOrder && driver.homeRidesLeft > 0) {
                 driver.homeRidesLeft -= 1
                 driverRepository.save(driver)
             }
        }

        val saved = orderRepository.save(order)
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
    
    fun getActiveOrdersForDispatcher(): List<TaxiOrderDto> {
        val activeStatuses = listOf(
            OrderStatus.REQUESTED, 
            OrderStatus.OFFERING, 
            OrderStatus.ACCEPTED, 
            OrderStatus.DRIVER_ARRIVED, 
            OrderStatus.IN_PROGRESS
        )
        return orderRepository.findAllByStatusIn(activeStatuses)
            .map { TaxiOrderDto(it) }
            .sortedByDescending { it.id }
    }

    fun mapToDto(order: TaxiOrder): TaxiOrderDto = TaxiOrderDto(order)
}