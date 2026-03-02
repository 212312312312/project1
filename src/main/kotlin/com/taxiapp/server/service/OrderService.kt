package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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
import com.taxiapp.server.service.NotificationService
import kotlin.math.max
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.service.SettingsService

@Service
class OrderService(
    private val chatService: ChatService,
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
    private val sectorService: SectorService,
    private val cancellationReasonRepository: CancellationReasonRepository,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val appSettingRepository: AppSettingRepository
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

        // ==========================================
        // ПЕРЕВІРКА ЛІМІТУ АКТИВНИХ ЗАМОВЛЕНЬ (Максимум 3)
        // ==========================================
        val activeStatuses = listOf(
            OrderStatus.REQUESTED,
            OrderStatus.OFFERING,
            OrderStatus.ACCEPTED,
            OrderStatus.DRIVER_ARRIVED,
            OrderStatus.IN_PROGRESS,
            OrderStatus.SCHEDULED
        )
        
        val activeOrdersCount = orderRepository.countActiveOrdersByClient(client.id!!, activeStatuses)
        if (activeOrdersCount >= 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ви не можете мати більше 3 активних замовлень одночасно")
        }
        // ==========================================

        // --- Валідація часу ---
        if (request.scheduledAt != null) {
            val now = LocalDateTime.now()
            
            // Мінімальний буфер часу для запланованого замовлення.
            // Оскільки сервер починає пошук водія за 30-35 хвилин до подачі, 
            // клієнт має створювати таке замовлення мінімум за 40 хвилин.
            val minValidTime = now.plusMinutes(40)

            if (request.scheduledAt.isBefore(minValidTime)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST, 
                    "Попереднє замовлення можна зробити мінімум за 40 хвилин до подачі"
                )
            }
            
            if (request.scheduledAt.isAfter(now.plusDays(7))) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST, 
                    "Не можна бронювати більше ніж на 7 днів наперед"
                )
            }
        }

    

        // --- Розрахунок ціни ---
        var finalPrice = calculateExactTripPrice(
            tariffId = request.tariffId,
            polyline = request.googleRoutePolyline,
            totalDistanceMeters = request.distanceMeters ?: 0,
            serviceIds = request.serviceIds,
            addedValue = request.addedValue ?: 0.0,
            isDebug = true
        )

        // --- Логіка знижок (Promo) ---
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

        // --- Визначення секторів ---
        val destSector = if (request.destLat != null && request.destLng != null) {
            sectorRepository.findSectorByCoordinates(request.destLat, request.destLng)
        } else null

        val originSector = if (request.originLat != null && request.originLng != null) {
            sectorRepository.findSectorByCoordinates(request.originLat, request.originLng)
        } else null

        val initialStatus = if (request.scheduledAt != null) OrderStatus.SCHEDULED else OrderStatus.REQUESTED

        // --- Створення об'єкта замовлення ---
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

        // --- Збереження запланованого замовлення ---
        if (initialStatus == OrderStatus.SCHEDULED) {
            logger.info("Order scheduled for ${newOrder.scheduledAt}")
            val savedScheduled = orderRepository.save(newOrder)
            broadcastOrderChange(savedScheduled, "ADD")
            return TaxiOrderDto(savedScheduled)
        }

        // =====================================================================
        // 🚀 ЛОГІКА РОЗПОДІЛУ (WATERFALL): Ланцюг -> Авто/Цикл -> Ефір
        // =====================================================================
        
        val rejectedIds = if (newOrder.rejectedDriverIds.isNotEmpty()) newOrder.rejectedDriverIds.toList() else null

        // 1. ЛАНЦЮГ (Chain) - тут оставляем "геометрический" поиск, так как это очень близко
        val chainDriver = driverRepository.findBestChainDriver(
            pickupLat = newOrder.originLat!!,
            pickupLng = newOrder.originLng!!,
            rejectedDriverIds = rejectedIds
        ).orElse(null)

        if (chainDriver != null) {
            // Теперь дистанция считается от точки ВЫСАДКИ (destLat) текущего заказа водителя
            // до точки ПОДАЧИ (originLat) нового заказа.
            // И используется личный радиус водителя.
            logger.info(">>> CHAIN driver found: ${chainDriver.id}")
            assignOrderToDriver(newOrder, chainDriver)
        } else {
            // 2. AUTO/CYCLE - УМНЫЙ ПОИСК
            // Сначала ищем по Авто-фильтрам (эксклюзив)
            val autoDriver = findDriverByAutoFilter(newOrder)

            if (autoDriver != null) {
                logger.info(">>> FILTER driver selected: ${autoDriver.id}")
                assignOrderToDriver(newOrder, autoDriver)
            } else {
                // 3. ETHER / BEST CANDIDATE (Эфир или поиск лучшего)
                
                // Получаем ТОП-5 ближайших по прямой
                val candidates = driverRepository.findBestDriversCandidates(
                    newOrder.originLat!!,
                    newOrder.originLng!!,
                    destSector?.id,
                    rejectedIds
                )

                if (candidates.isNotEmpty()) {
                    // Выбираем того, кто доедет быстрее всего (реальные дороги)
                    val bestDriver = selectFastestDriver(candidates, newOrder.originLat!!, newOrder.originLng!!)
                    
                    logger.info(">>> SMART Selection: Winner ${bestDriver.id} from ${candidates.size} candidates.")
                    assignOrderToDriver(newOrder, bestDriver)
                } else {
                    logger.info(">>> No drivers found. Sending to Ether.")
                    newOrder.status = OrderStatus.REQUESTED
                }
            }
        }

        val savedOrder = orderRepository.save(newOrder)
        if (savedOrder.status == OrderStatus.REQUESTED) {
            broadcastOrderChange(savedOrder, "ADD")
        }

        return TaxiOrderDto(savedOrder)
    }

    private fun assignOrderToDriver(order: TaxiOrder, driver: Driver) {
        val dist = calculateDistanceKm(
            driver.latitude ?: 0.0,
            driver.longitude ?: 0.0,
            order.originLat!!,
            order.originLng!!
        )
        logger.info(">>> Assigning order ${order.id} to driver ${driver.id} (Distance: ${String.format("%.2f", dist)} km)")

        order.status = OrderStatus.OFFERING
        order.offeredDriver = driver
        order.offerExpiresAt = LocalDateTime.now().plusSeconds(20)
        notificationService.sendOrderOffer(driver, order)
    }

    private fun selectFastestDriver(drivers: List<Driver>, targetLat: Double, targetLng: Double): Driver {
        // Простая эвристика: выбираем того, кто ближе, но с учетом "коэффициента извилистости"
        return drivers.minByOrNull { driver ->
            estimateDrivingTimeSeconds(driver.latitude!!, driver.longitude!!, targetLat, targetLng)
        } ?: drivers.first()
    }

    private fun estimateDrivingTimeSeconds(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val distKm = calculateDistanceKm(lat1, lon1, lat2, lon2)
        val realDistKm = distKm * 1.4 // Учитываем повороты
        val speedKmh = 30.0 // Средняя скорость в городе
        return (realDistKm / speedKmh) * 3600
    }


    
    private fun findDriverByAutoFilter(order: TaxiOrder): Driver? {
        val rejectedIds = if (order.rejectedDriverIds.isNotEmpty()) order.rejectedDriverIds.toList() else null

        // Викликаємо метод репозиторію, який ми створили раніше
        val filters = filterRepository.findMatchingAutoFilters(
            orderLat = order.originLat ?: 0.0,
            orderLng = order.originLng ?: 0.0,
            orderSectorId = order.originSector?.id,
            orderPrice = order.price,
            rejectedDriverIds = rejectedIds
        )

        // Якщо знайшли фільтри, беремо першого водія
        // (Можна додати сортування по дистанції, якщо репозиторій повертає кілька)
        return filters.firstOrNull()?.driver
    }

    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371 // Радиус Земли
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    @Transactional
    fun rejectOffer(driver: Driver, orderId: Long) {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        
        if (order.status == OrderStatus.OFFERING && order.offeredDriver?.id == driver.id) {
            logger.info("Водій ${driver.id} відхилив пропозицію ${order.id}")
            
            // --- ЛОГИКА ШТРАФА ---
            // Если заказ был в статусе OFFERING и имел назначенного водителя, значит это был
            // либо АВТО, либо ЦЕПОЧКА, либо ПЕРСОНАЛЬНОЕ предложение.
            // За отказ - штраф.
            
            driverActivityService.updateScore(driver, -50, "Відмова від запропонованого замовлення #${order.id}")
            
            order.rejectedDriverIds.add(driver.id!!)
            order.status = OrderStatus.REQUESTED // Сбрасываем в Эфир
            order.offeredDriver = null
            order.offerExpiresAt = null
            
            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD") // Уведомляем всех в Эфире
        }
    }

    @Scheduled(fixedRate = 5000) // Увеличили интервал до 5 секунд, чтобы не спамить БД
    @Transactional
    fun checkExpiredOffers() {
        val now = LocalDateTime.now()
        // Теперь база данных САМА отдает только просроченные предложения (мгновенно)
        val expiredOrders = orderRepository.findAllByStatusAndOfferExpiresAtBefore(OrderStatus.OFFERING, now)

        for (order in expiredOrders) {
            logger.info("Час пропозиції замовлення ${order.id} вичерпано. Штраф і перехід в Ефір.")
            
            order.offeredDriver?.let { 
                 driverActivityService.updateScore(it, -50, "Пропущено замовлення #${order.id}")
                 order.rejectedDriverIds.add(it.id!!)
            }

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
        // Адміни бачать все
        val message = OrderSocketMessage(action, order.id!!, if (action == "ADD") orderDto else null)
        sendSocketAfterCommit("/topic/admin/orders", message)

        // Якщо це заплановане замовлення без водія - воно повинно бути видно
        // Якщо у замовлення вже є водій - його не повинен бачити ніхто, крім цього водія

        val onlineDrivers = driverRepository.findAll().filter { it.isOnline }

        for (driver in onlineDrivers) {
            if (driver.activityScore <= 0) continue

            // --- ВИПРАВЛЕННЯ 1: ЛОГІКА ВИДИМОСТІ ---
            
            // Якщо ми видаляємо замовлення взагалі
            if (action == "REMOVE") {
                val msgRemove = OrderSocketMessage("REMOVE", order.id!!, null)
                messagingTemplate.convertAndSend("/topic/drivers/${driver.id}/orders", msgRemove)
                continue
            }

            // Якщо у замовлення Є водій, і це НЕ поточний водій -> надсилаємо REMOVE (хованки)
            if (order.driver != null && order.driver!!.id != driver.id) {
                val msgRemove = OrderSocketMessage("REMOVE", order.id!!, null)
                messagingTemplate.convertAndSend("/topic/drivers/${driver.id}/orders", msgRemove)
                continue
            }
            // ---------------------------------------

            val activeFilters = filterRepository.findAllByDriverId(driver.id!!)
                .filter { it.isActive }
            
            val shouldSend = if (activeFilters.isEmpty()) {
                true 
            } else {
                activeFilters.any { filter -> 
                    filter.isEther && matchesFilter(order, filter, driver) 
                }
            }

            if (shouldSend) {
                // Якщо це заплановане і воно "моє" (я його взяв), або воно нічиє
                val msgAdd = OrderSocketMessage(action, order.id!!, orderDto)
                sendSocketAfterCommit("/topic/drivers/${driver.id}/orders", msgAdd)
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

        // --- ВИПРАВЛЕННЯ: Використовуємо новий метод, щоб приховати зайняті SCHEDULED ---
        val allOrders = orderRepository.findAllAvailableForEther()
        // -------------------------------------------------------------------------------

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
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (order.client.id != user.id) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        
        // <<< NEW: Логика уведомления водителя >>>
        val assignedDriver = order.driver
        if (assignedDriver != null && (order.status == OrderStatus.ACCEPTED || order.status == OrderStatus.DRIVER_ARRIVED)) {
            notificationService.saveAndSend(
                driver = assignedDriver,
                title = "Замовлення скасовано",
                body = "Клієнт скасував замовлення за адресою ${order.fromAddress}",
                type = "ORDER_CANCEL"
            )
        }
        // ----------------------------------------

        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)
        
        // <--- ОЧИСТКА ЧАТА --->
        chatService.clearChatForOrder(orderId) 
        
        broadcastOrderChange(saved, "REMOVE")
        return TaxiOrderDto(saved)
    }

    @Transactional
    fun driverCancelOrder(driver: Driver, orderId: Long, reasonId: Long?): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        }

        // --- ЛОГІКА ПРИЧИН ОТМЕНЫ ---
        var penalty = 50 
        var reasonText = "Не вказано"

        if (reasonId != null) {
            val reasonObj = cancellationReasonRepository.findById(reasonId).orElse(null)
            if (reasonObj != null) {
                penalty = reasonObj.penaltyScore
                reasonText = reasonObj.reasonText
            }
        }

        // --- ВИПРАВЛЕННЯ 2: ПОВЕРНЕННЯ В ЕФІР ДЛЯ ЗАПЛАНОВАНИХ ---
        if (order.status == OrderStatus.SCHEDULED) {
            logger.info("Driver ${driver.id} rejected SCHEDULED order #${order.id}. Returning to Ether.")
            
            // Штрафуємо (якщо треба)
            driverActivityService.processOrderCancellation(driver, orderId, penalty, "$reasonText (Відмова від запланованого)")
            
            // Очищаємо водія, але не скасовуємо замовлення повністю
            order.rejectedDriverIds.add(driver.id!!)
            order.driver = null
            order.isDriverConfirmed = false
            order.confirmationRequestedAt = null
            order.offeredDriver = null
            
            // Замовлення залишається SCHEDULED, але тепер без водія -> broadcast надішле його всім
            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD") 
            
            return TaxiOrderDto(saved)
        }
        // ---------------------------------------------------------

        // Стандартне скасування для активних замовлень
        order.cancellationReason = reasonText
        driverActivityService.processOrderCancellation(driver, orderId, penalty, reasonText)

        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)

        // <--- ОЧИСТКА ЧАТА --->
        chatService.clearChatForOrder(orderId)

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
        
        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE") // <-- ДОБАВЛЕНО: Мгновенное оповещение
        
        return TaxiOrderDto(savedOrder)
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
        order.startedAt = LocalDateTime.now()

        // --- ЛОГИКА РАСЧЕТА ПЛАТНОГО ОЖИДАНИЯ ---
        if (order.arrivedAt != null) {
            val minutesWaited = java.time.temporal.ChronoUnit.MINUTES.between(order.arrivedAt, order.startedAt).toInt()
            val freeMinutes = order.tariff.freeWaitingMinutes
            
            if (minutesWaited > freeMinutes) {
                val paidMinutes = minutesWaited - freeMinutes
                val extraCost = paidMinutes * order.tariff.pricePerWaitingMinute
                
                order.waitingPrice = extraCost
                order.price += extraCost // Добавляем к общей стоимости заказа
                
                logger.info("Paid waiting applied for Order ${order.id}: $paidMinutes mins. Extra cost: $extraCost")
            }
        }
        // ---------------------------------------

        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE") // <-- ДОБАВЛЕНО: Рассылка новой цены и статуса
        
        return TaxiOrderDto(savedOrder)
    }

    @Transactional
    fun confirmScheduledOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        if (order.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        }
        if (order.status != OrderStatus.SCHEDULED) {
            // Якщо замовлення вже ACCEPTED, просто повертаємо ОК, нічого не міняємо
            return TaxiOrderDto(order)
        }

        logger.info("Driver ${driver.id} CONFIRMED scheduled order #${order.id}")
        
        // 1. Ставимо мітку підтвердження
        order.isDriverConfirmed = true
        
        // 2. Переводимо в статус ACCEPTED (Активне)
        // Тепер воно поводиться як звичайне замовлення: можна натиснути "На місці"
        order.status = OrderStatus.ACCEPTED
        
        val saved = orderRepository.save(order)
        broadcastOrderChange(saved, "ADD") // Оновлюємо статус у всіх
        return TaxiOrderDto(saved)
    }

    @Transactional
    fun completeOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        
        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN)

        order.status = OrderStatus.COMPLETED
        order.completedAt = LocalDateTime.now()

        // 1. Оновлюємо статистику поїздок
        driver.completedRides += 1
        driverActivityService.processOrderCompletion(driver, order)

        // =======================================================
        // 💰 ФИНАНСОВЫЙ БЛОК: РАСЧЕТ И СПИСАНИЕ КОМИССИИ
        // =======================================================
        
        // 1. Получаем актуальный процент комиссии через сервис (вместо ручного поиска в репо)
        // (Предполагается, что ты добавил settingsService в конструктор)
        // Если еще не добавил settingsService в конструктор, используй старый способ:
        val commissionSetting = appSettingRepository.findById("driver_commission_percent").orElse(null)
        val commissionPercent = commissionSetting?.value?.toDoubleOrNull() ?: 10.0 // Дефолт 10%
        
        // 2. Считаем сумму комиссии
        // Комиссия берется от полной стоимости поездки
        val commissionAmount = order.price * (commissionPercent / 100.0)

        // 3. Списываем с баланса водителя
        driver.balance -= commissionAmount

        // 4. Записываем комиссию в заказ
        order.commissionAmount = commissionAmount

        // 5. Сохраняем историю транзакции
        // Важно: TransactionType.COMMISSION должен существовать в enum
        val transaction = com.taxiapp.server.model.finance.WalletTransaction(
            driver = driver,
            amount = -commissionAmount, // Списание — отрицательное число
            operationType = com.taxiapp.server.model.enums.TransactionType.COMMISSION,
            orderId = order.id,
            description = "Комісія ${String.format("%.1f", commissionPercent)}% за замовлення #${order.id}"
        )
        walletTransactionRepository.save(transaction)
        
        // =======================================================

        driverRepository.save(driver) // Сохраняем обновленный баланс водителя

        // Отключаем Авто-фильтры после успешного завершения (если так задумано)
        val filters = filterRepository.findAllByDriverId(driver.id!!)
        for (f in filters) {
            if (f.isActive && f.isAuto) {
                f.isAuto = false 
                if (!f.isEther && !f.isCycle) {
                    f.isActive = false
                }
                filterRepository.save(f)
            }
        }

        // Обработка промокодов и программ лояльности
        if (order.appliedDiscount > 0.0) {
            if (order.isPromoCodeUsed) {
                val activePromoUsage = promoCodeService.findActiveUsage(order.client)
                activePromoUsage?.let { promoCodeService.markAsUsed(it.id) }
            } else {
                promoService.markRewardAsUsed(order.client)
            }
        }

        promoService.updateProgressOnRideCompletion(order.client, order)
        
        val savedOrder = orderRepository.save(order)
        
        // <--- ОЧИСТКА ЧАТА --->
        chatService.clearChatForOrder(orderId)

        // Уведомляем диспетчера, что заказ закрыт
        broadcastOrderChange(savedOrder, "UPDATE") 
        
        return TaxiOrderDto(savedOrder)
    }
    
    @Scheduled(fixedRate = 60000) // Перевірка кожну хвилину
    @Transactional
    fun activateScheduledOrders() {
        val now = LocalDateTime.now()
        // Шукаємо замовлення, де час подачі настане через 30-35 хвилин (вікно активації)
        // АБО ті, які вже прострочені (для очищення)
        val checkThreshold = now.plusMinutes(35) 

        val pendingOrders = orderRepository.findAllByStatusAndScheduledAtBefore(OrderStatus.SCHEDULED, checkThreshold)

        for (order in pendingOrders) {
            if (order.scheduledAt == null) continue

            // 1. Якщо замовлення безнадійно прострочене (більше години) -> Скасовуємо
            if (order.scheduledAt!!.isBefore(now.minusHours(1))) {
                logger.warn("Scheduled order ${order.id} expired. Cancelling.")
                order.status = OrderStatus.CANCELLED
                orderRepository.save(order)
                
                // <--- ОЧИСТКА ЧАТА --->
                chatService.clearChatForOrder(order.id!!)
                
                continue
            }

            // Ми працюємо тільки з тими, де до подачі залишилось <= 30 хвилин
            // (але більше ніж -1 година, щоб не чіпати старі)
            if (order.scheduledAt!!.isAfter(now.plusMinutes(31))) {
                // Ще рано (більше 31 хвилини до подачі)
                continue 
            }

            // === ЛОГІКА ПІДТВЕРДЖЕННЯ (ЗА 30 ХВ) ===
            
            if (order.driver != null) {
                // ВИПРАВЛЕНО: Додано "== true", бо поле може бути null
                if (order.isDriverConfirmed == true) {
                    if (order.status == OrderStatus.SCHEDULED) {
                        order.status = OrderStatus.ACCEPTED
                        orderRepository.save(order)
                        broadcastOrderChange(order, "ADD")
                    }
                    continue
                }

                // Водій є, але ще не підтвердив
                if (order.confirmationRequestedAt == null) {
                logger.info("Asking confirmation for order #${order.id}")
                order.confirmationRequestedAt = LocalDateTime.now()
                orderRepository.save(order)
                
                // ВИПРАВЛЕНО: Викликаємо новий метод сповіщення
                notificationService.sendConfirmationRequest(order.driver!!, order) 
            } else {
                    // КРОК 2: Перевіряємо тайм-аут (1 хвилина)
                    if (now.isAfter(order.confirmationRequestedAt!!.plusMinutes(1))) {
                        logger.warn("Driver ${order.driver!!.id} failed to confirm order #${order.id}. PENALTY applied.")
                        
                        // ШТРАФ
                        driverActivityService.processOrderCancellation(order.driver!!, order.id!!, 50, "Не підтвердив заплановане")
                        
                        // Знімаємо водія
                        order.rejectedDriverIds.add(order.driver!!.id!!)
                        order.driver = null
                        order.isDriverConfirmed = false
                        order.confirmationRequestedAt = null
                        
                        // Статус залишається SCHEDULED, але тепер без водія -> піде в пошук
                        val saved = orderRepository.save(order)
                        broadcastOrderChange(saved, "ADD") // Повертається в ефір
                    }
                }
            } 
            // === ЛОГІКА ПОШУКУ НОВОГО ВОДІЯ ===
            else {
                // Водія немає, час підтискає (<= 30 хв). Шукаємо активно.
                logger.info("Searching driver for scheduled order #${order.id}")
                
                // Ставимо REQUESTED/OFFERING щоб запустити механізм торгів, 
                // але статус самої сутності в базі краще тримати SCHEDULED або REQUESTED
                
                // Спробуємо знайти водія
                val bestDriver = driverRepository.findBestDriverForOrder(
                    order.originLat ?: 0.0,
                    order.originLng ?: 0.0,
                    order.destinationSector?.id,
                    order.rejectedDriverIds.toList()
                ).orElse(null)

                if (bestDriver != null) {
                    logger.info("Offering scheduled order #${order.id} to ${bestDriver.id}")
                    order.status = OrderStatus.OFFERING
                    order.offeredDriver = bestDriver
                    order.offerExpiresAt = LocalDateTime.now().plusSeconds(20)
                    notificationService.sendOrderOffer(bestDriver, order)
                    orderRepository.save(order)
                } else {
                    // Нікого немає - просто висить в ефірі
                    if (order.status != OrderStatus.REQUESTED) {
                        order.status = OrderStatus.REQUESTED
                        orderRepository.save(order)
                        broadcastOrderChange(order, "ADD")
                    }
                }
            }
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

    private fun sendSocketAfterCommit(destination: String, message: Any) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    messagingTemplate.convertAndSend(destination, message)
                }
            })
        } else {
            messagingTemplate.convertAndSend(destination, message)
        }
    }

    fun mapToDto(order: TaxiOrder): TaxiOrderDto = TaxiOrderDto(order)
}