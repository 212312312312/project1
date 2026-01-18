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
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

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

        // --- НОВА ЛОГІКА РОЗРАХУНКУ ЦІНИ (МІСТО vs ЗА МІСТОМ) ---
        var calculatedPrice = 0.0
        
        if (!request.googleRoutePolyline.isNullOrEmpty()) {
            // Отримуємо всі сектори, які помічені як "Місто" (isCity = true)
            val citySectors = sectorRepository.findAll().filter { it.isCity }
            
            // Отримуємо розбивку дистанції в метрах (потрібен оновлений GeometryUtils!)
            val (metersCity, metersOutCity) = GeometryUtils.calculateRouteSplit(request.googleRoutePolyline, citySectors)
            
            val totalKmCity = metersCity / 1000.0
            val totalKmOutCity = metersOutCity / 1000.0
            
            // --- ЛОГІКА МІНІМАЛКИ (3 КМ) ---
            val INCLUDED_KM = 3.0
            var remainingIncluded = INCLUDED_KM

            // 1. Списуємо з міста
            var billableKmCity = totalKmCity
            if (billableKmCity > remainingIncluded) {
                billableKmCity -= remainingIncluded
                remainingIncluded = 0.0
            } else {
                remainingIncluded -= billableKmCity
                billableKmCity = 0.0
            }

            // 2. Списуємо із заміста
            var billableKmOutCity = totalKmOutCity
            if (remainingIncluded > 0) {
                if (billableKmOutCity > remainingIncluded) {
                    billableKmOutCity -= remainingIncluded
                    remainingIncluded = 0.0 
                } else {
                    billableKmOutCity = 0.0
                }
            }

            // Рахуємо тільки платні кілометри
            val routePrice = (billableKmCity * tariff.pricePerKm) + (billableKmOutCity * tariff.pricePerKmOutCity)
            
            calculatedPrice = tariff.basePrice + routePrice
            
            // Логуємо для налагодження (ВИПРАВЛЕНО ЗМІННІ)
            logger.info("Order Price Calc: City=${totalKmCity}km, Out=${totalKmOutCity}km. Price=$calculatedPrice")
        } else {
            // Якщо полілінії немає (ручний режим або помилка), рахуємо просто за загальним кілометражем по міському тарифу
            // Тут теж застосуємо логіку мінімалки для справедливості
            val totalKm = (request.distanceMeters ?: 0) / 1000.0
            val INCLUDED_KM = 3.0
            val billableKm = if (totalKm > INCLUDED_KM) totalKm - INCLUDED_KM else 0.0
            
            calculatedPrice = tariff.basePrice + (billableKm * tariff.pricePerKm)
        }

        // Додаємо "Чайові" або ручну націнку клієнта
        calculatedPrice += (request.addedValue ?: 0.0)

        // Фінальна ціна перед знижками (округляємо до цілого вгору)
        var finalPrice = Math.ceil(calculatedPrice)

        // --- ЛОГІКА ЗНИЖОК ---
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
        if (finalPrice < tariff.basePrice) finalPrice = tariff.basePrice // Не менше мінімалки

        // --- ВИЗНАЧЕННЯ СЕКТОРА ПРИЗНАЧЕННЯ (для "Додому") ---
        val destSector = if (request.destLat != null && request.destLng != null) {
            sectorService.findSectorByCoordinates(request.destLat, request.destLng)
        } else null
        // ------------------------------------------------------------------

        // 4. Створення об'єкта замовлення
        val newOrder = TaxiOrder(
            client = client,
            fromAddress = request.fromAddress,
            toAddress = request.toAddress,
            status = OrderStatus.REQUESTED, 
            createdAt = LocalDateTime.now(),
            tariff = tariff,
            price = finalPrice, // <-- ВИКОРИСТОВУЄМО РОЗРАХОВАНУ ЦІНУ
            
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

            // Зберігаємо сектор куди їдемо
            destinationSector = destSector
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

        // --- SMART DISPATCH: Шукаємо водія для Ланцюга або Додому ---
        val rejectedIds = if (newOrder.rejectedDriverIds.isNotEmpty()) {
            newOrder.rejectedDriverIds.toList()
        } else {
            null
        }

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
            
            // ВІДПРАВЛЯЄМО ПОВІДОМЛЕННЯ
            notificationService.sendOrderOffer(bestDriver, newOrder)
        } else {
            // НЕ ЗНАЙШЛИ -> В загальний Ефір
            newOrder.status = OrderStatus.REQUESTED
        }

        val savedOrder = orderRepository.save(newOrder)

        // Якщо статус REQUESTED -> розсилаємо всім ("Ефір")
        if (savedOrder.status == OrderStatus.REQUESTED) {
            broadcastOrderChange(savedOrder, "ADD")
        }

        return TaxiOrderDto(savedOrder)
    }

    // --- ЛОГІКА REAL-TIME ПОВІДОМЛЕНЬ (WEBSOCKET) ---
    @Transactional
    fun rejectOffer(driver: Driver, orderId: Long) {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        
        // Перевіряємо, чи це замовлення справді запропоноване цьому водію
        if (order.status == OrderStatus.OFFERING && order.offeredDriver?.id == driver.id) {
            logger.info("Водій ${driver.id} відхилив замовлення ${order.id}")

            // 1. Додаємо водія в "чорний список" цього замовлення
            order.rejectedDriverIds.add(driver.id!!)

            // 2. Переводимо замовлення в Ефір (REQUESTED)
            order.status = OrderStatus.REQUESTED
            order.offeredDriver = null
            order.offerExpiresAt = null
            
            val saved = orderRepository.save(order)
            
            // 3. Сповіщаємо всіх про нове замовлення в ефірі
            broadcastOrderChange(saved, "ADD") 
        }
    }

    @Scheduled(fixedRate = 1000)
    @Transactional
    fun checkExpiredOffers() {
        val now = LocalDateTime.now()
        // Шукаємо всі замовлення, які "висять" на водієві, але час вийшов
        val expiredOrders = orderRepository.findAllByStatus(OrderStatus.OFFERING)
            .filter { it.offerExpiresAt != null && it.offerExpiresAt!!.isBefore(now) }

        for (order in expiredOrders) {
            logger.info("Час пропозиції замовлення ${order.id} вичерпано. Переведення в Ефір.")
            
            // Запам'ятовуємо, що цей водій "проґавив" замовлення
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

    // --- ГОЛОВНА ЛОГІКА ФІЛЬТРАЦІЇ ---

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
                    // Сортування точок
                    val sorted = sector.points.sortedBy { it.pointOrder }
                    GeometryUtils.isPointInPolygon(order.originLat ?: 0.0, order.originLng ?: 0.0, sorted)
                }
                if (!isInStartSector) return false
            }
        }

        // 2. Блок КУДИ
        if (filter.toSectors.isNotEmpty()) {
            val endSectors = sectorRepository.findAllById(filter.toSectors)
            val isInEndSector = endSectors.any { sector ->
                val sorted = sector.points.sortedBy { it.pointOrder }
                GeometryUtils.isPointInPolygon(order.destLat ?: 0.0, order.destLng ?: 0.0, sorted)
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
        // 1. Спочатку шукаємо замовлення, де водій вже ПРИЗНАЧЕНИЙ (driver_id)
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

        // 2. Якщо активних немає, шукаємо ПРОПОЗИЦІЮ (offered_driver_id)
        // Це критично для екрану OrderOffer!
        val offeredOrder = orderRepository.findAllByStatus(OrderStatus.OFFERING)
            .find { it.offeredDriver?.id == driver.id }

        return if (offeredOrder != null) {
            // Перевіряємо, чи не сплив час (на всяк випадок)
            if (offeredOrder.offerExpiresAt != null && LocalDateTime.now().isAfter(offeredOrder.offerExpiresAt)) {
                null 
            } else {
                TaxiOrderDto(offeredOrder)
            }
        } else {
            null
        }
    }

    // НОВЫЙ ВСПОМОГАТЕЛЬНЫЙ МЕТОД
    private fun findOfferedOrder(driver: Driver): TaxiOrderDto? {
        // Ищем заказ, где этот водитель указан как offeredDriver и статус OFFERING
        return orderRepository.findAllByStatus(OrderStatus.OFFERING)
            .find { it.offeredDriver?.id == driver.id }
            ?.let { TaxiOrderDto(it) }
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

    // Це метод для КЛІЄНТА (або адміна)
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

    // --- МЕТОД ДЛЯ ВОДІЯ (ЗІ ШТРАФОМ) ---
    @Transactional
    fun driverCancelOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        }

        // Штраф за скасування
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
        
        // --- ЛОГІКА ПЕРЕВІРКИ СТАТУСУ ---
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
        
        // --- ЛОГІКА "ДОДОМУ" ---
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

    // --- ПОВЕРНУТІ МЕТОДИ ---
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